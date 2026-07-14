package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.ws.ErrorPayload;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import fr.pivot.collaboratif.whiteboard.ws.WhiteboardPresenceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final SimpMessagingTemplate messagingTemplate;
    private final CanvasEventRepository canvasEventRepository;
    private final CardRepository cardRepository;
    private final ColorPaletteService colorPaletteService;
    private final ParticipantMetaStore participantMetaStore;
    private final BoardMemberRepository boardMemberRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final WhiteboardPresenceRegistry presenceRegistry;
    private final ParticipantsBroadcastService participantsBroadcastService;

    /**
     * Creates the service.
     *
     * @param messagingTemplate            STOMP broadcast template
     * @param canvasEventRepository        JPA repository for DRAW persistence
     * @param cardRepository               JPA repository for durable {@link Card} state (EN08.4)
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
     */
    public CanvasActionService(
            final SimpMessagingTemplate messagingTemplate,
            final CanvasEventRepository canvasEventRepository,
            final CardRepository cardRepository,
            final ColorPaletteService colorPaletteService,
            final ParticipantMetaStore participantMetaStore,
            final BoardMemberRepository boardMemberRepository,
            final ObjectMapper objectMapper,
            final MeterRegistry meterRegistry,
            final WhiteboardPresenceRegistry presenceRegistry,
            final ParticipantsBroadcastService participantsBroadcastService) {
        this.messagingTemplate = messagingTemplate;
        this.canvasEventRepository = canvasEventRepository;
        this.cardRepository = cardRepository;
        this.colorPaletteService = colorPaletteService;
        this.participantMetaStore = participantMetaStore;
        this.boardMemberRepository = boardMemberRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.presenceRegistry = presenceRegistry;
        this.participantsBroadcastService = participantsBroadcastService;
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
        if (isCardMutation(eventType) && isViewer(boardId, principal.userId())) {
            // Deliberately no dedicated error frame here, unlike UNDO above — a silent refusal
            // matches the reference whiteboard's behaviour for every card:* mutation (§3.12 of
            // the parity spec), whereas UNDO's targeted error is a pre-existing, unrelated
            // behaviour of this repo that EN08.4 does not extend to card mutations (Gate 1
            // decision, see EN08.4's backlog file).
            LOG.warn("Card mutation rejected: VIEWER role cannot write — type={} user={} board={}",
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
            // RESET is server-emitted only (US08.2.4, via the REST reset endpoint) — a client
            // must never be able to trigger a canvas reset over STOMP, so an inbound RESET
            // frame is silently dropped here (same policy as an unknown type).
            case RESET -> LOG.warn(
                    "Inbound RESET dropped — RESET is server-emitted only, board={} user={}",
                    boardId, principal.userId());
        }
    }

    /**
     * Returns whether the given event type mutates the durable {@link Card} table and
     * therefore requires {@code canWrite} (OWNER or EDITOR) — every {@code CARD_*} type
     * except none (all seven are mutations; there is no read-only card action over STOMP,
     * board-state on JOIN being the read path).
     *
     * @param eventType the event type to check
     * @return {@code true} for any {@code CARD_*} type
     */
    private boolean isCardMutation(final CanvasEventType eventType) {
        return switch (eventType) {
            case CARD_CREATE, CARD_MOVE, CARD_RESIZE, CARD_UPDATE, CARD_RECOLOR, CARD_DELETE, CARD_LAYER -> true;
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
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("cards", cards);
        stateData.put("connections", List.of());
        stateData.put("frames", List.of());
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
     * reconcile its own optimistic local object with the server-assigned id).
     */
    private void handleCardCreate(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        CardType type = parseCardType(data.get("type"));
        String content = (String) data.getOrDefault("content", "");
        double posX = toDouble(data.get("posX"), 0);
        double posY = toDouble(data.get("posY"), 0);

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
        LOG.debug("Card created: id={} board={} type={}", card.getId(), boardId, type);
    }

    /**
     * Handles a CARD_MOVE action: moves a card if it exists, belongs to this board, and is
     * not locked; refused silently otherwise (no broadcast, no error frame).
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
        broadcast(boardId, principal, CanvasEventType.CARD_MOVE,
                Map.of("id", id.toString(), "posX", posX, "posY", posY));
    }

    /**
     * Handles a CARD_RESIZE action: resizes a card if it exists, belongs to this board, and
     * is not locked; refused silently otherwise.
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
        broadcast(boardId, principal, CanvasEventType.CARD_RESIZE,
                Map.of("id", id.toString(), "width", width, "height", height));
    }

    /**
     * Handles a CARD_UPDATE action: updates a card's content if it exists, belongs to this
     * board, and is not locked; refused silently otherwise.
     */
    private void handleCardUpdate(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
            return;
        }
        String content = (String) data.getOrDefault("content", "");
        int updated = cardRepository.updateContentIfUnlocked(id, boardId, content);
        if (updated == 0) {
            LOG.debug("Card update refused (locked, missing, or cross-board): id={} board={}", id, boardId);
            return;
        }
        broadcast(boardId, principal, CanvasEventType.CARD_UPDATE,
                Map.of("id", id.toString(), "content", content));
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
     * Handles a CARD_DELETE action: deletes a card scoped by board. Idempotent — deleting an
     * id that does not exist (already deleted, wrong board, or never existed) is a silent
     * no-op, never an exception. Deliberately not guarded by {@code locked} in this Socle.
     */
    private void handleCardDelete(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        UUID id = parseCardId(data.get("id"));
        if (id == null) {
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
}
