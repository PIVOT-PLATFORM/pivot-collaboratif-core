package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardConnectionDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.FrameDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.canvas.opengraph.CardContentEnrichmentRequestedEvent;
import fr.pivot.collaboratif.whiteboard.canvas.table.TableCardContentSanitizer;
import fr.pivot.collaboratif.whiteboard.ws.ErrorPayload;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import fr.pivot.collaboratif.whiteboard.ws.WhiteboardPresenceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for whiteboard canvas STOMP actions (US08.3.1).
 *
 * <p>Dispatches incoming canvas action messages after type validation, enforces role
 * constraints (VIEWER cannot send UNDO), persists DRAW events in the database, and
 * broadcasts results to the appropriate STOMP topics.
 *
 * <p>Broadcast destinations:
 * <ul>
 *   <li>{@code /topic/whiteboard/{boardId}} — all canvas actions (JOIN, LEAVE, DRAW,
 *       CURSOR_MOVE, UNDO, CARD_*) enriched with server-side fields (colour for JOIN), plus
 *       the {@code board:state} reply on JOIN (below).</li>
 *   <li>{@code /topic/whiteboard/{boardId}/presence} — PARTICIPANTS_UPDATE with the
 *       full list of connected participants, emitted on every JOIN and LEAVE.</li>
 * </ul>
 *
 * <p><strong>Sender exclusion for {@code card:moved}/{@code card:resized} (fix/EN08.4).</strong>
 * Every broadcast still goes to the whole room — the simple broker has no built-in
 * "send to all but one" primitive on a shared topic, and building a per-subscriber targeted
 * fanout server-side would be disproportionate for this Socle. Instead, {@link #handleCardMove}
 * and {@link #handleCardResize} alone echo back a client-supplied {@code senderSessionId} field
 * (an opaque, client-generated correlation id — not the server's STOMP {@code sessionId}; see
 * {@link #handleCardMove}'s Javadoc for why) when the incoming action carries one; the frontend
 * filters client-side, ignoring the echo when {@code senderSessionId} matches its own value
 * (avoids the visual jitter of re-applying a move/resize it already applied optimistically).
 * {@code card:recolored}/{@code card:deleted} are deliberately left unchanged — broadcast to the
 * whole room including the sender, same as before this fix. {@code card:updated} is unaffected
 * by sender exclusion too, but its payload shape did change in this same fix — see
 * {@link #handleCardUpdate}'s Javadoc.
 *
 * <p><strong>Wire naming</strong> is resolved via {@link CanvasEventType#fromWire} (incoming)
 * and {@link CanvasEventType#wireOut()} (outgoing) — see that class's Javadoc for why these
 * differ from the bare Java enum name (EN08.4 recette finding, #68).
 *
 * <p><strong>{@code board:state} on JOIN</strong> is broadcast to the whole room on the main
 * topic (not a per-user queue) — the frontend's {@code StompBoardTransport} subscribes to a
 * single topic and has no per-user queue subscription, so a targeted
 * {@code convertAndSendToUser} reply would never reach it. Every already-connected client
 * harmlessly re-applies the same (idempotent) state; {@code role} is deliberately omitted
 * from this payload since it is per-recipient and this is a room-wide broadcast — role stays
 * authoritative via the REST board GET.
 *
 * <p>Conflict strategy: Last-Write-Wins — the most recently received DRAW event wins.
 * No OT/CRDT resolution is implemented in the Socle.
 */
@Service
@Transactional
public class CanvasActionService {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasActionService.class);

    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    /** Micrometer counter name for throttled canvas messages (declared in US08.3.1). */
    static final String THROTTLED_COUNTER = "messages.throttled.total";

    /**
     * Finite, whitelisted set of connector line shapes accepted by
     * {@link #handleConnectionUpdate} — English wire values matching {@link CardConnection}'s
     * own creation-time default ({@code curved}, US08.7.1). A {@code shape} outside this set is
     * rejected for that field alone (US08.7.2, AC5).
     */
    static final Set<String> ALLOWED_CONNECTION_SHAPES = Set.of("straight", "curved", "orthogonal");

    /**
     * Finite, whitelisted set of connector arrowhead styles accepted by
     * {@link #handleConnectionUpdate} — English wire values matching {@link CardConnection}'s
     * own creation-time default ({@code none}, US08.7.1). An {@code arrow} outside this set is
     * rejected for that field alone (US08.7.2, AC5).
     */
    static final Set<String> ALLOWED_CONNECTION_ARROWS = Set.of("none", "start", "end", "both");

    private final SimpMessagingTemplate messagingTemplate;
    private final CanvasEventRepository canvasEventRepository;
    private final CardRepository cardRepository;
    private final CardConnectionRepository cardConnectionRepository;
    private final FrameRepository frameRepository;
    private final ColorPaletteService colorPaletteService;
    private final ParticipantMetaStore participantMetaStore;
    private final BoardMemberRepository boardMemberRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final WhiteboardPresenceRegistry presenceRegistry;
    private final ParticipantsBroadcastService participantsBroadcastService;
    private final ApplicationEventPublisher eventPublisher;
    private final ShapeStyleSanitizer shapeStyleSanitizer;
    private final ImageCardContentValidator imageCardContentValidator;
    private final TableCardContentSanitizer tableCardContentSanitizer;

    /**
     * Creates the service.
     *
     * @param messagingTemplate            STOMP broadcast template
     * @param canvasEventRepository        JPA repository for DRAW persistence
     * @param cardRepository               JPA repository for durable {@link Card} state (EN08.4)
     * @param cardConnectionRepository     JPA repository for durable {@link CardConnection}
     *                                     state (US08.7.1)
     * @param frameRepository              JPA repository for durable {@link Frame} state (EN08, Frames)
     * @param colorPaletteService          deterministic colour assignment
     * @param participantMetaStore         Redis store for participant metadata
     * @param boardMemberRepository        JPA repository for role lookups
     * @param objectMapper                 Jackson 3 mapper for payload serialisation
     * @param meterRegistry                Micrometer registry for operational metrics
     * @param presenceRegistry             session-liveness registry, updated on JOIN/LEAVE so
     *                                     that a later WebSocket disconnect can tell whether it
     *                                     was the user's last active session on the board
     *                                     (resolution of #32)
     * @param participantsBroadcastService shared PARTICIPANTS_UPDATE broadcaster, also used by
     *                                     {@link WhiteboardPresenceRegistry} on disconnect
     *                                     cleanup — single source of truth for this topic
     * @param eventPublisher                Spring event bus — used to request an asynchronous
     *                                       OpenGraph enrichment pass after a LINK/TEXT/LABEL
     *                                       card is created or updated (US08.6.5); see {@link
     *                                       CardContentEnrichmentRequestedEvent}
     * @param shapeStyleSanitizer            sanitises {@link CardType#SHAPE} style content
     *                                       ({@code kind}/{@code stroke}/{@code fill}/
     *                                       {@code opacity}/{@code rotation}, pipe-delimited)
     *                                       before persistence (US08.6.3, correctif §6.4)
     * @param imageCardContentValidator      server-side MIME/size validation for
     *                                       {@link CardType#IMAGE} content (US08.6.4)
     * @param tableCardContentSanitizer      defence-in-depth sanitizer applied to every
     *                                       CARD_CREATE/CARD_UPDATE content string — a no-op
     *                                       for any content that is not TABLE-shaped (US08.6.6)
     */
    public CanvasActionService(
            final SimpMessagingTemplate messagingTemplate,
            final CanvasEventRepository canvasEventRepository,
            final CardRepository cardRepository,
            final CardConnectionRepository cardConnectionRepository,
            final FrameRepository frameRepository,
            final ColorPaletteService colorPaletteService,
            final ParticipantMetaStore participantMetaStore,
            final BoardMemberRepository boardMemberRepository,
            final ObjectMapper objectMapper,
            final MeterRegistry meterRegistry,
            final WhiteboardPresenceRegistry presenceRegistry,
            final ParticipantsBroadcastService participantsBroadcastService,
            final ApplicationEventPublisher eventPublisher,
            final ShapeStyleSanitizer shapeStyleSanitizer,
            final ImageCardContentValidator imageCardContentValidator,
            final TableCardContentSanitizer tableCardContentSanitizer) {
        this.messagingTemplate = messagingTemplate;
        this.canvasEventRepository = canvasEventRepository;
        this.cardRepository = cardRepository;
        this.cardConnectionRepository = cardConnectionRepository;
        this.frameRepository = frameRepository;
        this.colorPaletteService = colorPaletteService;
        this.participantMetaStore = participantMetaStore;
        this.boardMemberRepository = boardMemberRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.presenceRegistry = presenceRegistry;
        this.participantsBroadcastService = participantsBroadcastService;
        this.eventPublisher = eventPublisher;
        this.shapeStyleSanitizer = shapeStyleSanitizer;
        this.imageCardContentValidator = imageCardContentValidator;
        this.tableCardContentSanitizer = tableCardContentSanitizer;
    }

    /**
     * Handles an incoming canvas action from an authenticated STOMP session.
     *
     * <p>Validates the message type against the whitelist, enforces role constraints
     * for UNDO (VIEWER blocked), and dispatches to the appropriate handler.
     *
     * @param boardId   the target board UUID (from the STOMP destination path variable)
     * @param message   the incoming canvas action
     * @param principal the authenticated STOMP session principal
     * @param sessionId the STOMP session ID of the sender (used for the presence liveness
     *                  registry on JOIN/LEAVE, resolution of #32)
     */
    public void handle(
            final UUID boardId,
            final CanvasActionMessage message,
            final StompPrincipal principal,
            final String sessionId) {
        if (message.type() == null) {
            LOG.warn("Received canvas message with null type — board={} user={}", boardId, principal.userId());
            return;
        }
        CanvasEventType eventType = CanvasEventType.fromWire(message.type());
        if (eventType == null) {
            LOG.warn("Unknown canvas action type '{}' — dropped board={} user={}",
                    message.type(), boardId, principal.userId());
            return;
        }
        if (eventType == CanvasEventType.UNDO && isViewer(boardId, principal.userId())) {
            LOG.warn("UNDO rejected: VIEWER role cannot undo — user={} board={}", principal.userId(), boardId);
            messagingTemplate.convertAndSendToUser(
                    principal.getName(), "/queue/errors",
                    new ErrorPayload("VIEWER role cannot send UNDO"));
            return;
        }
        if (requiresCanWrite(eventType) && isViewer(boardId, principal.userId())) {
            // Deliberately no dedicated error frame here, unlike UNDO above — a silent refusal
            // matches the reference whiteboard's behaviour for every card:*/connection:*
            // mutation (§3.12/§3.6 of the parity spec), whereas UNDO's targeted error is a
            // pre-existing, unrelated behaviour of this repo that EN08.4/US08.7.1 do not extend
            // to card/connection mutations (Gate 1 decision, see EN08.4's backlog file).
            LOG.warn("Mutation rejected: VIEWER role cannot write — type={} user={} board={}",
                    eventType, principal.userId(), boardId);
            return;
        }
        switch (eventType) {
            case JOIN -> handleJoin(boardId, message, principal, sessionId);
            case LEAVE -> handleLeave(boardId, principal, sessionId);
            case DRAW -> handleDraw(boardId, message, principal);
            case CURSOR_MOVE -> handleCursorMove(boardId, message, principal);
            case UNDO -> handleUndo(boardId, message, principal);
            case CARD_CREATE -> handleCardCreate(boardId, message, principal);
            case CARD_MOVE -> handleCardMove(boardId, message, principal);
            case CARD_RESIZE -> handleCardResize(boardId, message, principal);
            case CARD_UPDATE -> handleCardUpdate(boardId, message, principal);
            case CARD_RECOLOR -> handleCardRecolor(boardId, message, principal);
            case CARD_DELETE -> handleCardDelete(boardId, message, principal);
            case CARD_LAYER -> handleCardLayer(boardId, message, principal);
            case CONNECTION_CREATE -> handleConnectionCreate(boardId, message, principal);
            case CONNECTION_DELETE -> handleConnectionDelete(boardId, message, principal);
            case CONNECTION_UPDATE -> handleConnectionUpdate(boardId, message, principal);
            case FRAME_CREATE -> handleFrameCreate(boardId, message, principal);
            case FRAME_MOVE -> handleFrameMove(boardId, message, principal);
            case FRAME_RESIZE -> handleFrameResize(boardId, message, principal);
            case FRAME_UPDATE -> handleFrameUpdate(boardId, message, principal);
            case FRAME_DELETE -> handleFrameDelete(boardId, message, principal);
            case FRAME_LAYER -> handleFrameLayer(boardId, message, principal);
            // RESET is server-emitted only (US08.2.4, via the REST reset endpoint) — a client
            // must never be able to trigger a canvas reset over STOMP, so an inbound RESET
            // frame is silently dropped here (same policy as an unknown type).
            case RESET -> LOG.warn(
                    "Inbound RESET dropped — RESET is server-emitted only, board={} user={}",
                    boardId, principal.userId());
        }
    }

    /**
     * Returns whether the given event type mutates a durable board-state table ({@link Card},
     * {@link CardConnection} or {@link Frame}) and therefore requires {@code canWrite} (OWNER or
     * EDITOR) — every {@code CARD_*}, {@code CONNECTION_*} and {@code FRAME_*} type (there is no
     * read-only card/connection/frame action over STOMP, board-state on JOIN being the read path).
     *
     * @param eventType the event type to check
     * @return {@code true} for any {@code CARD_*}/{@code CONNECTION_*}/{@code FRAME_*} type
     */
    private boolean requiresCanWrite(final CanvasEventType eventType) {
        return switch (eventType) {
            case CARD_CREATE, CARD_MOVE, CARD_RESIZE, CARD_UPDATE, CARD_RECOLOR, CARD_DELETE, CARD_LAYER,
                    CONNECTION_CREATE, CONNECTION_DELETE, CONNECTION_UPDATE,
                    FRAME_CREATE, FRAME_MOVE, FRAME_RESIZE, FRAME_UPDATE, FRAME_DELETE, FRAME_LAYER -> true;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Private handlers
    // -------------------------------------------------------------------------

    /**
     * Handles a JOIN action: assigns colour, stores participant metadata, registers the
     * session in the presence liveness registry, broadcasts the JOIN event, emits
     * PARTICIPANTS_UPDATE, and broadcasts a {@code board:state} snapshot of the board's
     * current cards to the whole room (see the class-level Javadoc for why this is a room
     * broadcast rather than a targeted per-user reply).
     *
     * <p>Colour assignment is deterministic by {@code userId} ({@link ColorPaletteService}),
     * so a reconnection or a duplicate JOIN from another tab of the same user keeps the same
     * colour. {@link ParticipantMetaStore#put} overwrites any existing entry for the same
     * {@code userId}, so a duplicate JOIN (multi-tab) results in a single participant entry
     * reflecting the most recent JOIN's metadata — "last active connection wins".
     */
    private void handleJoin(
            final UUID boardId,
            final CanvasActionMessage message,
            final StompPrincipal principal,
            final String sessionId) {
        Map<String, Object> data = asMap(message.data());
        String displayName = (String) data.getOrDefault("displayName", "Anonymous");
        String avatarUrl = (String) data.get("avatarUrl");
        String color = colorPaletteService.colorForUser(principal.userId());
        String role = resolveRoleName(boardId, principal.userId());

        ParticipantInfo info = new ParticipantInfo(
                principal.userId().toString(), displayName, avatarUrl, color, role);
        participantMetaStore.put(principal.tenantId(), boardId, info);
        presenceRegistry.registerSession(principal.tenantId(), boardId, principal.userId(), sessionId);

        Map<String, Object> broadcastData = new HashMap<>();
        broadcastData.put("displayName", displayName);
        broadcastData.put("avatarUrl", avatarUrl);
        broadcastData.put("color", color);
        broadcastData.put("role", role);

        broadcast(boardId, principal, CanvasEventType.JOIN, broadcastData);
        participantsBroadcastService.broadcast(principal.tenantId(), boardId);

        List<CardDto> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, principal.tenantId())
                .stream()
                .map(this::toDto)
                .toList();
        List<CardConnectionDto> connections = cardConnectionRepository
                .findAllByBoardIdAndTenantId(boardId, principal.tenantId())
                .stream()
                .map(this::toDto)
                .toList();
        List<FrameDto> frames = frameRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, principal.tenantId())
                .stream()
                .map(this::toDto)
                .toList();
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("cards", cards);
        stateData.put("connections", connections);
        stateData.put("frames", frames);
        stateData.put("fields", List.of());
        broadcast(boardId, principal, "board:state", stateData);

        LOG.info("Canvas JOIN: board={} user={} displayName={}", boardId, principal.userId(), displayName);
    }

    /**
     * Handles a LEAVE action: removes participant metadata, unregisters the session from the
     * presence liveness registry, broadcasts LEAVE and emits PARTICIPANTS_UPDATE.
     *
     * <p>An explicit LEAVE always clears the participant's presence unconditionally — it does
     * not wait for every session/tab of the user to have left. This mirrors the pre-#32
     * behaviour and is intentionally different from a WebSocket disconnect without a prior
     * LEAVE, which only clears presence when it was the user's last active session
     * ({@link WhiteboardPresenceRegistry#handleDisconnect}).
     */
    private void handleLeave(final UUID boardId, final StompPrincipal principal, final String sessionId) {
        participantMetaStore.remove(principal.tenantId(), boardId, principal.userId());
        presenceRegistry.unregisterSession(principal.tenantId(), boardId, principal.userId(), sessionId);
        broadcast(boardId, principal, CanvasEventType.LEAVE, Map.of());
        participantsBroadcastService.broadcast(principal.tenantId(), boardId);
        LOG.info("Canvas LEAVE: board={} user={}", boardId, principal.userId());
    }

    /**
     * Handles a DRAW action: persists the event in the database and broadcasts.
     * Persistence implements Last-Write-Wins (Socle strategy).
     */
    private void handleDraw(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        String payloadJson = serialise(data);

        CanvasEvent event = new CanvasEvent(
                UUID.randomUUID(), boardId, principal.tenantId(), principal.userId(),
                CanvasEventType.DRAW, payloadJson, OffsetDateTime.now());
        canvasEventRepository.save(event);

        broadcast(boardId, principal, CanvasEventType.DRAW, data);
        LOG.debug("Canvas DRAW persisted: eventId={} board={} user={}", event.getId(), boardId, principal.userId());
    }

    /**
     * Handles a CURSOR_MOVE action: broadcasts only, no persistence (high-frequency
     * ephemeral data not worth storing).
     */
    private void handleCursorMove(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        broadcast(boardId, principal, CanvasEventType.CURSOR_MOVE, data);
    }

    /**
     * Handles an UNDO action: broadcasts for visual synchronisation; stack logic is
     * delegated to US08.3.3.
     */
    private void handleUndo(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        broadcast(boardId, principal, CanvasEventType.UNDO, data);
        LOG.debug("Canvas UNDO broadcast: board={} user={}", boardId, principal.userId());
    }

    /**
     * Handles a CARD_CREATE action: persists a new {@link Card} and broadcasts it.
     *
     * <p>{@code type} is parsed tolerantly — an unknown or missing value falls back to
     * {@link CardType#TEXT}, never an exception (parity spec §3.4). {@code clientTag}, if
     * present, is echoed back in the broadcast but never persisted (lets the sending client
     * reconcile its own optimistic local object with the server-assigned id). For
     * {@link CardType#SHAPE} exactly (never for a mistyped value that fell back to
     * {@link CardType#TEXT}), the incoming {@code content} — the pipe-delimited style string
     * ({@code kind|stroke|fill|opacity|rotation}) — is sanitised by
     * {@link ShapeStyleSanitizer} before persistence (US08.6.3, correctif §6.4).
     *
     * <p><strong>{@code type == IMAGE}</strong> (US08.6.4): {@code content} is passed through
     * {@link ImageCardContentValidator#sanitize} before persistence — real MIME sniffing and a
     * size bound, a hardening over the reference whiteboard's unvalidated {@code coverImage}
     * (parity spec §2.7/§6.12, flagged explicitly in this US's Security AC). An invalid image
     * (malformed data URL, oversized, or unrecognised signature) silently drops the whole
     * {@code CARD_CREATE} — no card persisted, no broadcast — consistent with every other
     * {@code card:*} refusal path in this Socle.
     *
     * <p>Before any type-specific handling, {@code content} first passes through
     * {@link TableCardContentSanitizer} — a defence-in-depth pass that is a no-op for any
     * content that is not TABLE-shaped (US08.6.6), so it never interferes with the
     * SHAPE/IMAGE-specific sanitisation that follows.
     */
    private void handleCardCreate(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        CardType type = parseCardType(data.get("type"));
        String content = tableCardContentSanitizer.sanitize((String) data.getOrDefault("content", ""));
        if (type == CardType.SHAPE) {
            content = shapeStyleSanitizer.sanitize(content);
        }
        double posX = toDouble(data.get("posX"), 0);
        double posY = toDouble(data.get("posY"), 0);

        if (type == CardType.IMAGE) {
            Optional<String> sanitizedContent = imageCardContentValidator.sanitize(content);
            if (sanitizedContent.isEmpty()) {
                LOG.warn("Card create refused: invalid IMAGE content — board={} user={}",
                        boardId, principal.userId());
                return;
            }
            content = sanitizedContent.get();
        }

        Card card = new Card(boardId, principal.tenantId(), type, content, posX, posY, Instant.now());
        if (data.get("color") instanceof String c) {
            card.setColor(c);
        }
        if (data.get("width") != null) {
            card.setWidth(toDouble(data.get("width"), card.getWidth()));
        }
        if (data.get("height") != null) {
            card.setHeight(toDouble(data.get("height"), card.getHeight()));
        }
        if (data.get("layer") != null) {
            card.setLayer((int) toDouble(data.get("layer"), card.getLayer()));
        }
        cardRepository.save(card);

        Map<String, Object> broadcastData = new HashMap<>();
        broadcastData.put("card", toDto(card));
        if (data.get("clientTag") != null) {
            broadcastData.put("clientTag", data.get("clientTag"));
        }
        broadcast(boardId, principal, CanvasEventType.CARD_CREATE, broadcastData);
        // US08.6.5 hand-off: request an async OpenGraph enrichment pass. CardUrlExtractor
        // decides internally whether `type`/`content` are eligible (LINK, or TEXT/LABEL with a
        // detected URL) — a no-op for every other card type.
        eventPublisher.publishEvent(
                new CardContentEnrichmentRequestedEvent(card.getId(), boardId, principal.tenantId(), type, content));
        LOG.debug("Card created: id={} board={} type={}", card.getId(), boardId, type);
    }

    /**
     * Handles a CARD_MOVE action: moves a card if it exists, belongs to this board, and is
     * not locked; refused silently otherwise (no broadcast, no error frame).
     *
     * <p>If the incoming action carries a client-supplied {@code senderSessionId} (an opaque,
     * client-generated correlation id — one per {@code StompBoardTransport} connection, not the
     * server's STOMP session id), it is echoed back verbatim in the broadcast payload, same
     * idiom as {@code clientTag} on {@link #handleCardCreate}: never persisted, only round-
     * tripped so the sending client can recognise and ignore its own echo (it already applied
     * the move optimistically) while every other session in the room still applies it normally.
     *
     * <p><strong>Why not the server's own STOMP session id.</strong> An earlier version of this
     * fix threaded the real {@code simpSessionId} through and tried to hand it back to the
     * client via a stamped header on the STOMP {@code CONNECTED} frame (a
     * {@code clientOutboundChannel} interceptor). That does not work with this repo's
     * {@code SimpleBroker}: {@code StompSubProtocolHandler#convertConnectAcktoStompConnected}
     * unconditionally rebuilds the CONNECTED frame's headers from scratch, copying only
     * {@code version}/{@code heartbeat} — any other native header added upstream is silently
     * discarded before the frame reaches the wire (verified empirically, reproduced against a
     * real STOMP client in a Testcontainers IT). Round-tripping a client-generated id sidesteps
     * this Spring limitation entirely and needed no other change to this repo's WebSocket
     * config.
     *
     * <p>This is a client-side filter, not a server-side targeted fanout: the simple broker
     * still sends this broadcast to the whole room (see {@link #broadcast}), same as every
     * other {@code CARD_*} event — only the payload optionally gains this one extra field
     * (fix/EN08.4, sender exclusion for {@code card:moved}/{@code card:resized} only).
     *
     * @param boardId   the board UUID
     * @param message   the incoming CARD_MOVE action
     * @param principal the emitting principal
     */
    private void handleCardMove(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        double posX = toDouble(data.get("posX"), 0);
        double posY = toDouble(data.get("posY"), 0);
        int updated = cardRepository.moveIfUnlocked(id, boardId, posX, posY);
        if (updated == 0) {
            LOG.debug("Card move refused (locked, missing, or cross-board): id={} board={}", id, boardId);
            return;
        }
        Map<String, Object> broadcastData = new HashMap<>();
        broadcastData.put("id", id.toString());
        broadcastData.put("posX", posX);
        broadcastData.put("posY", posY);
        if (data.get("senderSessionId") != null) {
            broadcastData.put("senderSessionId", data.get("senderSessionId"));
        }
        broadcast(boardId, principal, CanvasEventType.CARD_MOVE, broadcastData);
    }

    /**
     * Handles a CARD_RESIZE action: resizes a card if it exists, belongs to this board, and
     * is not locked; refused silently otherwise.
     *
     * <p>Echoes back a client-supplied {@code senderSessionId} for the same sender-exclusion
     * reason as {@link #handleCardMove} — see that method's Javadoc for the full rationale,
     * including why this is a client-generated correlation id and not the server's STOMP
     * session id.
     *
     * @param boardId   the board UUID
     * @param message   the incoming CARD_RESIZE action
     * @param principal the emitting principal
     */
    private void handleCardResize(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        double width = toDouble(data.get("width"), 192);
        double height = toDouble(data.get("height"), 128);
        int updated = cardRepository.resizeIfUnlocked(id, boardId, width, height);
        if (updated == 0) {
            LOG.debug("Card resize refused (locked, missing, or cross-board): id={} board={}", id, boardId);
            return;
        }
        Map<String, Object> broadcastData = new HashMap<>();
        broadcastData.put("id", id.toString());
        broadcastData.put("width", width);
        broadcastData.put("height", height);
        if (data.get("senderSessionId") != null) {
            broadcastData.put("senderSessionId", data.get("senderSessionId"));
        }
        broadcast(boardId, principal, CanvasEventType.CARD_RESIZE, broadcastData);
    }

    /**
     * Handles a CARD_UPDATE action: updates a card's content if it exists, belongs to this
     * board, and is not locked; refused silently otherwise. Before any type-specific handling,
     * {@code content} first passes through {@link TableCardContentSanitizer} — a
     * defence-in-depth pass that is a no-op for any content that is not TABLE-shaped
     * (US08.6.6). The card's persisted type is then looked up once before the atomic guarded
     * write, and used to dispatch further type-specific content handling:
     * <ul>
     *   <li>{@link CardType#SHAPE}: {@code content} is sanitised by
     *       {@link ShapeStyleSanitizer}, same as at creation (US08.6.3, correctif §6.4).</li>
     *   <li>{@link CardType#IMAGE} (US08.6.4 Gate 4 hardening): {@code content} is passed
     *       through {@link ImageCardContentValidator#sanitize} — otherwise a raw STOMP client
     *       could bypass the UI's upload flow entirely and persist an unvalidated value
     *       (including an external URL) directly onto an existing IMAGE card, which is later
     *       rendered as an image {@code src}. Mirrors the guard already applied in
     *       {@link #handleCardCreate}. An invalid image content refuses the whole update.</li>
     * </ul>
     * This pre-read does not weaken the atomic {@code locked}/board-ownership guard on the
     * mutation query itself ({@link CardRepository#updateContentIfUnlocked}).
     *
     * <p>Broadcasts the full updated {@link CardDto} (re-read after the update), not just
     * {@code {id, content}} — matching {@code card:created}/{@code card:moved}/
     * {@code card:resized}/{@code card:recolored}, all of which broadcast a complete object,
     * and the parity contract the six Sprint 12 card-type US all specify identically for
     * {@code card:updated} (gap found independently by the US08.6.1 TEXT agent, PR core#77 —
     * flagged there without fixing, folded into the Socle foundation fix instead).
     */
    private void handleCardUpdate(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        String content = tableCardContentSanitizer.sanitize((String) data.getOrDefault("content", ""));
        CardType type = cardRepository.findTypeByIdAndBoardId(id, boardId).orElse(null);
        if (type == CardType.SHAPE) {
            content = shapeStyleSanitizer.sanitize(content);
        } else if (type == CardType.IMAGE) {
            Optional<String> sanitizedContent = imageCardContentValidator.sanitize(content);
            if (sanitizedContent.isEmpty()) {
                LOG.warn("Card update refused: invalid IMAGE content — id={} board={} user={}",
                        id, boardId, principal.userId());
                return;
            }
            content = sanitizedContent.get();
        }
        int updated = cardRepository.updateContentIfUnlocked(id, boardId, content);
        if (updated == 0) {
            LOG.debug("Card update refused (locked, missing, or cross-board): id={} board={}", id, boardId);
            return;
        }
        Card card = cardRepository.findById(id).orElse(null);
        if (card == null) {
            // Extremely unlikely race (deleted concurrently between the UPDATE above and this
            // read) — nothing meaningful left to broadcast; the client that deleted it already
            // gets its own card:deleted broadcast.
            LOG.debug("Card update broadcast skipped: card vanished after update, id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.CARD_UPDATE, toFlatMap(toDto(card)));
        // US08.6.5 hand-off (see handleCardCreate) — content just changed, so re-run enrichment:
        // either a new/changed URL gets (re-)fetched, or its removal clears a previous preview.
        // Reuses the `card` just re-read above rather than a second lookup.
        eventPublisher.publishEvent(
                new CardContentEnrichmentRequestedEvent(
                        id, boardId, principal.tenantId(), card.getType(), content));
    }

    /**
     * Handles a CARD_RECOLOR action: recolors a card if it exists, belongs to this board, and
     * is not locked; refused silently otherwise.
     */
    private void handleCardRecolor(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        String color = (String) data.getOrDefault("color", "#FFEB3B");
        int updated = cardRepository.recolorIfUnlocked(id, boardId, color);
        if (updated == 0) {
            LOG.debug("Card recolor refused (locked, missing, or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.CARD_RECOLOR,
                Map.of("id", id.toString(), "color", color));
    }

    /**
     * Handles a CARD_DELETE action: deletes a card scoped by board, guarded by an explicit
     * {@code locked} read performed <em>before</em> the delete itself (fix/EN08.4 — the six
     * Sprint 12 card-type US all specify this guard identically: a locked card refuses
     * {@code card:delete} silently, same posture as move/resize/update/recolor). Unlike those
     * four, this guard cannot live in the {@code WHERE} clause of a single
     * {@code UPDATE}/{@code DELETE} statement, since there is nothing left to condition on once
     * the row is gone — hence the separate read here rather than a query-level change to
     * {@link CardRepository#deleteByIdAndBoardId}.
     *
     * <p>Idempotent — deleting an id that does not exist (already deleted, wrong board, or
     * never existed) is a silent no-op, never an exception: a missing card skips the lock
     * check entirely (nothing to refuse) and falls through to
     * {@link CardRepository#deleteByIdAndBoardId}, which itself resolves to {@code 0} rows
     * affected.
     */
    private void handleCardDelete(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        Optional<Card> existing = cardRepository.findById(id);
        if (existing.isPresent() && (existing.get().isLocked() || !existing.get().getBoardId().equals(boardId))) {
            LOG.debug("Card delete refused (locked or cross-board): id={} board={}", id, boardId);
            return;
        }
        long deleted = cardRepository.deleteByIdAndBoardId(id, boardId);
        if (deleted == 0) {
            LOG.debug("Card delete no-op (already deleted or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.CARD_DELETE, Map.of("id", id.toString()));
    }

    /**
     * Handles a CARD_LAYER action: changes a card's Z-order layer. Deliberately not guarded
     * by {@code locked} — the sole mutation the parity spec does not protect with the lock
     * (§4.6).
     */
    private void handleCardLayer(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        int layer = (int) toDouble(data.get("layer"), 1);
        int updated = cardRepository.updateLayer(id, boardId, layer);
        if (updated == 0) {
            LOG.debug("Card layer change no-op (missing or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.CARD_LAYER,
                Map.of("id", id.toString(), "layer", layer));
    }

    /**
     * Handles a CONNECTION_CREATE action: persists a new {@link CardConnection} linking two
     * cards of this board and broadcasts it to the whole room, emitter included (US08.7.1).
     *
     * <p>Every refusal below is silent — no broadcast, no exception, no dedicated STOMP error
     * frame — consistent with the reference whiteboard's behaviour for this mutation (parity
     * spec §3.6):
     * <ul>
     *   <li>{@code fromId}/{@code toId} missing or unparsable.</li>
     *   <li>Self-link ({@code fromId.equals(toId)}) — checked before any database access.</li>
     *   <li>{@code fromId} or {@code toId} does not reference an existing card of this board —
     *       validated with a single {@link CardRepository#countByIdInAndBoardId} call before
     *       insert (correctif §6.5: unlike the reference whiteboard, which lets Prisma throw an
     *       uncaught FK error here, this repo never lets that exception reach the handler).</li>
     *   <li>A connector already links this exact pair in either direction — bidirectional
     *       anti-duplicate ({@link CardConnectionRepository#existsBetween}).</li>
     * </ul>
     */
    private void handleConnectionCreate(
            final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID fromId = parseCardId(data.get("fromId"));
        UUID toId = parseCardId(data.get("toId"));
        if (fromId == null || toId == null) {
            return;
        }
        if (fromId.equals(toId)) {
            LOG.debug("Connection create refused (self-link): board={} cardId={}", boardId, fromId);
            return;
        }
        if (cardRepository.countByIdInAndBoardId(List.of(fromId, toId), boardId) != 2) {
            LOG.debug("Connection create refused (missing/foreign card ref): board={} from={} to={}",
                    boardId, fromId, toId);
            return;
        }
        if (cardConnectionRepository.existsBetween(boardId, fromId, toId)) {
            LOG.debug("Connection create refused (bidirectional duplicate): board={} from={} to={}",
                    boardId, fromId, toId);
            return;
        }
        CardConnection connection = new CardConnection(boardId, principal.tenantId(), fromId, toId, Instant.now());
        cardConnectionRepository.save(connection);

        broadcast(boardId, principal, CanvasEventType.CONNECTION_CREATE, Map.of("connection", toDto(connection)));
        LOG.debug("Connection created: id={} board={} from={} to={}", connection.getId(), boardId, fromId, toId);
    }

    /**
     * Handles a CONNECTION_DELETE action: deletes a connector scoped by board. Idempotent —
     * deleting an id that does not exist (already deleted, already cascaded away by one of its
     * endpoint cards being deleted, wrong board, or never existed) is a silent no-op, never an
     * exception (US08.7.1).
     */
    private void handleConnectionDelete(
            final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        long deleted = cardConnectionRepository.deleteByIdAndBoardId(id, boardId);
        if (deleted == 0) {
            LOG.debug("Connection delete no-op (already deleted or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.CONNECTION_DELETE, Map.of("id", id.toString()));
    }

    /**
     * Handles a CONNECTION_UPDATE action: applies a partial style patch to an existing
     * {@link CardConnection} and broadcasts the full updated connector to the whole room,
     * emitter included (US08.7.2).
     *
     * <p><strong>Presence vs. absence.</strong> Only the style keys actually present in the
     * incoming {@code data} map are considered — tested with {@link Map#containsKey}, never a
     * null-check on the retrieved value — so an absent key preserves the currently persisted
     * value while a present key carrying an explicit {@code null} clears it (parity spec
     * §1.8/§3.6). Only meaningful for {@code label}/{@code color} — the two nullable columns on
     * {@link CardConnection}; {@code shape}/{@code arrow}/{@code dashed}/{@code width} are all
     * {@code NOT NULL} columns, so an explicit {@code null} for one of those simply fails its
     * own type/whitelist check below and is skipped like any other invalid value, never
     * persisted as {@code null}.
     *
     * <p><strong>Field-level validation, not whole-patch rejection.</strong> {@code shape} and
     * {@code arrow} are checked against the finite applicative whitelists
     * {@link #ALLOWED_CONNECTION_SHAPES}/{@link #ALLOWED_CONNECTION_ARROWS} (English wire values
     * matching {@link CardConnection}'s own creation-time defaults, US08.7.1); {@code label}/
     * {@code color}/{@code dashed}/{@code width} are checked against their expected JSON type.
     * A present key whose value fails its check is simply skipped — the offending field is left
     * at its previous value — rather than aborting the whole patch or throwing, consistent with
     * every other tolerant {@code CARD_*}/{@code CONNECTION_*} handler in this class.
     *
     * <p><strong>No-op cases</strong> (silent — no database write, no broadcast, no exception,
     * no dedicated STOMP error frame):
     * <ul>
     *   <li>{@code id} missing or unparsable.</li>
     *   <li>{@code id} does not resolve to a connector of this board — unknown, already deleted,
     *       already cascaded away by an endpoint card's deletion, or belonging to another board
     *       (guessed or leaked cross-tenant id): {@link CardConnectionRepository#findByIdAndBoardId}
     *       scopes the lookup by {@code (id, boardId)}, so none of these leak whether the id
     *       exists elsewhere.</li>
     *   <li>No style key present in {@code data} beyond {@code id}/{@code boardId}, or every
     *       present style key was rejected by validation — nothing left to persist or
     *       broadcast.</li>
     * </ul>
     *
     * @param boardId   the board UUID
     * @param message   the incoming CONNECTION_UPDATE action
     * @param principal the emitting principal
     */
    private void handleConnectionUpdate(
            final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        Optional<CardConnection> existing = cardConnectionRepository.findByIdAndBoardId(id, boardId);
        if (existing.isEmpty()) {
            LOG.debug("Connection update no-op (missing or cross-board): id={} board={}", id, boardId);
            return;
        }
        CardConnection connection = existing.get();
        boolean mutated = applyConnectionPatch(connection, data);
        if (!mutated) {
            LOG.debug("Connection update no-op (empty or fully-rejected patch): id={} board={}", id, boardId);
            return;
        }
        cardConnectionRepository.save(connection);
        broadcast(boardId, principal, CanvasEventType.CONNECTION_UPDATE, Map.of("connection", toDto(connection)));
        LOG.debug("Connection updated: id={} board={}", id, boardId);
    }

    /**
     * Applies the style keys present in {@code data} onto {@code connection}, field by field —
     * see {@link #handleConnectionUpdate}'s Javadoc for the presence/absence and field-level
     * validation rules this implements.
     *
     * @param connection the connector to mutate in place (not yet persisted by this method)
     * @param data       the incoming action's field-accessible payload
     * @return {@code true} if at least one field was actually applied
     */
    private boolean applyConnectionPatch(final CardConnection connection, final Map<String, Object> data) {
        boolean mutated = false;
        if (data.containsKey("label")) {
            Object value = data.get("label");
            if (value == null || value instanceof String) {
                connection.setLabel((String) value);
                mutated = true;
            }
        }
        if (data.containsKey("color")) {
            Object value = data.get("color");
            if (value == null || value instanceof String) {
                connection.setColor((String) value);
                mutated = true;
            }
        }
        if (data.containsKey("shape")) {
            Object value = data.get("shape");
            if (value instanceof String s && ALLOWED_CONNECTION_SHAPES.contains(s)) {
                connection.setShape(s);
                mutated = true;
            } else {
                LOG.debug("Connection update: shape value rejected — connectionId={} value={}",
                        connection.getId(), value);
            }
        }
        if (data.containsKey("arrow")) {
            Object value = data.get("arrow");
            if (value instanceof String s && ALLOWED_CONNECTION_ARROWS.contains(s)) {
                connection.setArrow(s);
                mutated = true;
            } else {
                LOG.debug("Connection update: arrow value rejected — connectionId={} value={}",
                        connection.getId(), value);
            }
        }
        if (data.containsKey("dashed")) {
            Object value = data.get("dashed");
            if (value instanceof Boolean b) {
                connection.setDashed(b);
                mutated = true;
            }
        }
        if (data.containsKey("width")) {
            Object value = data.get("width");
            if (value instanceof Number n) {
                connection.setWidth(n.intValue());
                mutated = true;
            }
        }
        return mutated;
    }

    // -------------------------------------------------------------------------
    // Frame handlers (EN08, Frames)
    // -------------------------------------------------------------------------

    /**
     * Handles a FRAME_CREATE action ({@code frame:create} inbound): persists a new {@link Frame}
     * and broadcasts it as a flat frame object ({@code frame:created}) to the whole room, emitter
     * included.
     *
     * <p>The frontend's basic create sends only {@code {boardId, posX, posY}} and adopts whatever
     * the server echoes back; an optional {@code title}/{@code color}/{@code width}/{@code height}
     * (sent by the duplicate-frame path) is applied when present, otherwise the server-authoritative
     * defaults ({@link Frame}) stand. No {@code clientTag} is round-tripped — unlike cards, the
     * frontend does not send one for frames.
     *
     * @param boardId   the board UUID
     * @param message   the incoming FRAME_CREATE action
     * @param principal the emitting principal
     */
    private void handleFrameCreate(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        double posX = toDouble(data.get("posX"), 0);
        double posY = toDouble(data.get("posY"), 0);

        Frame frame = new Frame(boardId, principal.tenantId(), posX, posY, Instant.now());
        if (data.get("title") instanceof String t) {
            frame.setTitle(t);
        }
        if (data.get("color") instanceof String c) {
            frame.setColor(c);
        }
        if (data.get("width") != null) {
            frame.setWidth(toDouble(data.get("width"), frame.getWidth()));
        }
        if (data.get("height") != null) {
            frame.setHeight(toDouble(data.get("height"), frame.getHeight()));
        }
        if (data.get("layer") != null) {
            frame.setLayer((int) toDouble(data.get("layer"), frame.getLayer()));
        }
        frameRepository.save(frame);

        broadcast(boardId, principal, CanvasEventType.FRAME_CREATE, toFlatMap(toDto(frame)));
        LOG.debug("Frame created: id={} board={}", frame.getId(), boardId);
    }

    /**
     * Handles a FRAME_MOVE action ({@code frame:move} inbound): moves a frame if it exists and
     * belongs to this board, then broadcasts the full updated flat frame ({@code frame:moved}) —
     * the frontend spreads it over its local frame ({@code {...f, ...frame}}) and matches by id.
     * Refused silently (no broadcast) if the id is missing/unparsable or on another board.
     *
     * @param boardId   the board UUID
     * @param message   the incoming FRAME_MOVE action
     * @param principal the emitting principal
     */
    private void handleFrameMove(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        double posX = toDouble(data.get("posX"), 0);
        double posY = toDouble(data.get("posY"), 0);
        if (frameRepository.move(id, boardId, posX, posY) == 0) {
            LOG.debug("Frame move no-op (missing or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcastFrame(boardId, principal, CanvasEventType.FRAME_MOVE, id);
    }

    /**
     * Handles a FRAME_RESIZE action ({@code frame:resize} inbound): resizes a frame (width/height)
     * if it exists and belongs to this board, optionally moving it too when the payload also carries
     * {@code posX}/{@code posY}, then broadcasts the full updated flat frame ({@code frame:resized}).
     * Refused silently if the id is missing/unparsable or on another board.
     *
     * @param boardId   the board UUID
     * @param message   the incoming FRAME_RESIZE action
     * @param principal the emitting principal
     */
    private void handleFrameResize(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        double width = toDouble(data.get("width"), 400);
        double height = toDouble(data.get("height"), 300);
        if (frameRepository.resize(id, boardId, width, height) == 0) {
            LOG.debug("Frame resize no-op (missing or cross-board): id={} board={}", id, boardId);
            return;
        }
        // The frontend's commitResizeFrame path may also carry posX/posY (top-left anchor moves) —
        // apply them in the same action so the broadcast frame reflects the final geometry.
        if (data.get("posX") != null && data.get("posY") != null) {
            frameRepository.move(id, boardId, toDouble(data.get("posX"), 0), toDouble(data.get("posY"), 0));
        }
        broadcastFrame(boardId, principal, CanvasEventType.FRAME_RESIZE, id);
    }

    /**
     * Handles a FRAME_UPDATE action ({@code frame:update} inbound): applies a partial patch to a
     * frame's {@code title}/{@code active}/{@code color} — only the keys actually present in the
     * payload are mutated (the frontend sends {@code title} alone, or {@code active} alone) — then
     * broadcasts the full updated flat frame ({@code frame:updated}). Refused silently if the id is
     * missing/unparsable, on another board, or no recognised field was present.
     *
     * @param boardId   the board UUID
     * @param message   the incoming FRAME_UPDATE action
     * @param principal the emitting principal
     */
    private void handleFrameUpdate(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        Optional<Frame> existing = frameRepository.findByIdAndBoardId(id, boardId);
        if (existing.isEmpty()) {
            LOG.debug("Frame update no-op (missing or cross-board): id={} board={}", id, boardId);
            return;
        }
        Frame frame = existing.get();
        boolean mutated = false;
        if (data.get("title") instanceof String t) {
            frame.setTitle(t);
            mutated = true;
        }
        if (data.get("active") instanceof Boolean b) {
            frame.setActive(b);
            mutated = true;
        }
        if (data.get("color") instanceof String c) {
            frame.setColor(c);
            mutated = true;
        }
        if (!mutated) {
            LOG.debug("Frame update no-op (empty patch): id={} board={}", id, boardId);
            return;
        }
        frameRepository.save(frame);
        broadcast(boardId, principal, CanvasEventType.FRAME_UPDATE, toFlatMap(toDto(frame)));
        LOG.debug("Frame updated: id={} board={}", id, boardId);
    }

    /**
     * Handles a FRAME_DELETE action ({@code frame:delete} inbound): deletes a frame scoped by
     * board, then broadcasts {@code frame:deleted} carrying {@code {id}} — mirroring this
     * branch's {@code card:deleted} shape exactly (see {@link #handleCardDelete}). Idempotent:
     * deleting an id that does not exist (already deleted, on another board, or never existed)
     * is a silent no-op, never an exception.
     *
     * <p><strong>Wire-contract note.</strong> The frontend ({@code board.store.ts}) subscribes
     * with {@code this.on<string>('frame:deleted', id => …)} — i.e. it expects a <em>bare id
     * string</em>, not an {@code {id}} object. This branch deliberately emits the {@code {id}}
     * object to stay identical to {@code card:deleted}/{@code connection:deleted} as they exist
     * on this branch (the frontend is uniformly written for the post-{@code #84} envelope, where
     * every {@code *:deleted} becomes a bare string). Once {@code #84} (the wire-envelope PR)
     * lands, this line flips to {@code broadcast(..., id.toString())} together with the card and
     * connection deletes — a single mechanical change, made consistently across all three.
     *
     * @param boardId   the board UUID
     * @param message   the incoming FRAME_DELETE action
     * @param principal the emitting principal
     */
    private void handleFrameDelete(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        if (frameRepository.deleteByIdAndBoardId(id, boardId) == 0) {
            LOG.debug("Frame delete no-op (already deleted or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.FRAME_DELETE, Map.of("id", id.toString()));
    }

    /**
     * Handles a FRAME_LAYER action ({@code frame:layer} inbound): changes a frame's Z-order layer,
     * then echoes {@code {id, layer}} ({@code frame:layered}) — matching the frontend's
     * {@code this.on<{ id, layer }>('frame:layered', …)} (a lighter payload than the full frame,
     * same idiom as {@code card:layered}). Refused silently if the id is missing/unparsable or on
     * another board.
     *
     * @param boardId   the board UUID
     * @param message   the incoming FRAME_LAYER action
     * @param principal the emitting principal
     */
    private void handleFrameLayer(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        int layer = (int) toDouble(data.get("layer"), 0);
        if (frameRepository.updateLayer(id, boardId, layer) == 0) {
            LOG.debug("Frame layer no-op (missing or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.FRAME_LAYER,
                Map.of("id", id.toString(), "layer", layer));
    }

    /**
     * Re-reads a frame after a mutation and broadcasts its full flattened {@link FrameDto} under
     * the given event type ({@code frame:moved}/{@code frame:resized}). Skips the broadcast in the
     * extremely unlikely race where the frame vanished (concurrent delete) between the guarded
     * update and this read.
     *
     * @param boardId   the board UUID
     * @param principal the emitting principal
     * @param type      the outgoing event type
     * @param id        the mutated frame's id
     */
    private void broadcastFrame(
            final UUID boardId, final StompPrincipal principal, final CanvasEventType type, final UUID id) {
        Frame frame = frameRepository.findByIdAndBoardId(id, boardId).orElse(null);
        if (frame == null) {
            LOG.debug("Frame broadcast skipped: frame vanished after mutation, id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, type, toFlatMap(toDto(frame)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Broadcasts a canvas event to all subscribers of the board's main topic, under
     * {@code type}'s outgoing wire name ({@link CanvasEventType#wireOut()} — distinct from
     * the incoming name for {@code CARD_*} mutations, see that class's Javadoc).
     *
     * @param boardId   the board UUID
     * @param principal the emitting principal
     * @param type      the event type
     * @param data      the type-specific payload
     */
    private void broadcast(
            final UUID boardId,
            final StompPrincipal principal,
            final CanvasEventType type,
            final Map<String, Object> data) {
        broadcast(boardId, principal, type.wireOut(), data);
    }

    /**
     * Broadcasts a server-originated message with no corresponding inbound
     * {@link CanvasEventType} (e.g. {@code board:state}, the JOIN reply) under a raw wire
     * type string.
     *
     * @param boardId   the board UUID
     * @param principal the principal that triggered this broadcast
     * @param wireType  the raw outgoing wire type string
     * @param data      the type-specific payload
     */
    private void broadcast(
            final UUID boardId,
            final StompPrincipal principal,
            final String wireType,
            final Map<String, Object> data) {
        String destination = BOARD_TOPIC_PREFIX + boardId;
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                wireType, boardId.toString(), principal.userId().toString(), data);
        messagingTemplate.convertAndSend(destination, msg);
    }

    /**
     * Safely coerces an incoming action's polymorphic {@code data} to a field-accessible map,
     * for handlers that read named fields off it. {@code data} is a bare string (the board id)
     * for {@code board:join}/{@code board:leave} — those handlers don't need any field off it
     * (the board id already comes from the destination path variable), so falling back to an
     * empty map for a non-{@link Map} value is correct, not a data-loss workaround.
     *
     * @param rawData the raw {@link CanvasActionMessage#data()} value — a {@link Map},
     *                a {@link String}, or {@code null}
     * @return a string-keyed map, or an empty map if {@code rawData} isn't one
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object rawData) {
        return rawData instanceof Map<?, ?> ? (Map<String, Object>) rawData : Map.of();
    }

    /**
     * Resolves the role name string for a user on a board, defaulting to {@code "VIEWER"}
     * when the membership record is not found.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     * @return the role name (e.g. {@code "OWNER"}, {@code "EDITOR"}, {@code "VIEWER"})
     */
    private String resolveRoleName(final UUID boardId, final Long userId) {
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(m -> m.getRole().name())
                .orElse(BoardRole.VIEWER.name());
    }

    /**
     * Checks whether the given user has the VIEWER role on the board.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     * @return {@code true} if the user is a VIEWER (or membership not found)
     */
    private boolean isViewer(final UUID boardId, final Long userId) {
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(m -> m.getRole() == BoardRole.VIEWER)
                .orElse(true);
    }

    /**
     * Serialises a map to a JSON string using the auto-configured ObjectMapper.
     * Returns {@code "{}"} on serialisation failure (safeguard — should not occur
     * with a simple string/number map).
     *
     * @param data the data to serialise
     * @return JSON string
     */
    private String serialise(final Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            LOG.warn("Could not serialise canvas payload: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Parses a raw {@code type} value against {@link CardType}, falling back to
     * {@link CardType#TEXT} for {@code null}, blank, or unrecognised values — never throws
     * (parity spec §3.4: an unknown card type is dropped and the card falls back to TEXT,
     * not rejected with an error).
     *
     * @param rawType the raw value from the incoming message's {@code data} map
     * @return the parsed {@link CardType}, or {@link CardType#TEXT} as a fallback
     */
    private CardType parseCardType(final Object rawType) {
        if (!(rawType instanceof String s) || s.isBlank()) {
            return CardType.TEXT;
        }
        try {
            return CardType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CardType.TEXT;
        }
    }

    /**
     * Parses a raw card {@code id} value into a {@link UUID}, returning {@code null} (rather
     * than throwing) for a missing or malformed value so the caller can silently drop the
     * action — a forged/garbled id must never crash the STOMP session.
     *
     * @param rawId the raw value from the incoming message's {@code data} map
     * @return the parsed UUID, or {@code null} if missing/malformed
     */
    private UUID parseCardId(final Object rawId) {
        if (!(rawId instanceof String s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Coerces a JSON-deserialised numeric value (typically {@link Integer}, {@link Long}, or
     * {@link Double} depending on how Jackson represented the literal) to a {@code double},
     * without risking a {@link ClassCastException} from assuming one specific boxed type.
     *
     * @param rawValue     the raw value from the incoming message's {@code data} map
     * @param defaultValue the value to return if {@code rawValue} is not a {@link Number}
     * @return the numeric value as a {@code double}, or {@code defaultValue}
     */
    private double toDouble(final Object rawValue, final double defaultValue) {
        return rawValue instanceof Number n ? n.doubleValue() : defaultValue;
    }

    /**
     * Maps a persisted {@link Card} to its wire {@link CardDto}, parsing the opaque {@code meta}
     * JSONB column (if present) into a generic map.
     *
     * @param card the persisted card
     * @return the corresponding {@link CardDto}
     */
    @SuppressWarnings("unchecked")
    private CardDto toDto(final Card card) {
        Map<String, Object> meta = null;
        if (card.getMeta() != null) {
            try {
                meta = objectMapper.readValue(card.getMeta(), Map.class);
            } catch (Exception e) {
                LOG.warn("Could not parse card meta JSON: cardId={} error={}", card.getId(), e.getMessage());
            }
        }
        return CardDto.of(
                card.getId(), card.getType().name(), card.getContent(), meta,
                card.getPosX(), card.getPosY(), card.getWidth(), card.getHeight(), card.getColor(),
                card.getGroupId(), card.getGroupColor(), card.isLocked(), card.getLayer());
    }

    /**
     * Flattens a {@link CardDto} into a field-named {@link Map}, for broadcasts that put the
     * card's fields directly at the top level of {@code data} (e.g. {@code card:updated}) —
     * as opposed to {@link #handleCardCreate}'s {@code card:created}, which nests the DTO under
     * a {@code "card"} key. The frontend's {@code card:updated} handler ({@code board.store.ts})
     * expects the former shape, matching {@code card:moved}/{@code card:resized}/
     * {@code card:recolored}.
     *
     * @param dto the card DTO to flatten
     * @return a map of the DTO's fields, keyed by field name
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toFlatMap(final CardDto dto) {
        return objectMapper.convertValue(dto, Map.class);
    }

    /**
     * Maps a persisted {@link CardConnection} to its wire {@link CardConnectionDto}.
     *
     * @param connection the persisted connector
     * @return the corresponding {@link CardConnectionDto}
     */
    private CardConnectionDto toDto(final CardConnection connection) {
        return CardConnectionDto.of(
                connection.getId(), connection.getFromId(), connection.getToId(),
                connection.getLabel(), connection.getColor(), connection.getShape(),
                connection.getArrow(), connection.isDashed(), connection.getWidth());
    }

    /**
     * Maps a persisted {@link Frame} to its wire {@link FrameDto} (EN08, Frames).
     *
     * @param frame the persisted frame
     * @return the corresponding {@link FrameDto}
     */
    private FrameDto toDto(final Frame frame) {
        return FrameDto.of(
                frame.getId(), frame.getBoardId(), frame.getTitle(),
                frame.getPosX(), frame.getPosY(), frame.getWidth(), frame.getHeight(),
                frame.getColor(), frame.isActive(), frame.getLayer());
    }

    /**
     * Flattens a {@link FrameDto} into a field-named {@link Map}, for the {@code frame:created}/
     * {@code frame:moved}/{@code frame:resized}/{@code frame:updated} broadcasts that put the
     * frame's fields directly at the top level of {@code data} — matching the frontend's
     * {@code this.on<Frame>('frame:created', …)} handlers, which read the fields off {@code data}
     * directly (EN08, Frames).
     *
     * @param dto the frame DTO to flatten
     * @return a map of the DTO's fields, keyed by field name
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toFlatMap(final FrameDto dto) {
        return objectMapper.convertValue(dto, Map.class);
    }
}
