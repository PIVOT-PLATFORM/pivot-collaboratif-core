package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
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

import java.time.OffsetDateTime;
import java.util.HashMap;
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
 *       CURSOR_MOVE, UNDO) enriched with server-side fields (colour for JOIN).</li>
 *   <li>{@code /topic/whiteboard/{boardId}/presence} — PARTICIPANTS_UPDATE with the
 *       full list of connected participants, emitted on every JOIN and LEAVE.</li>
 * </ul>
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
            final ColorPaletteService colorPaletteService,
            final ParticipantMetaStore participantMetaStore,
            final BoardMemberRepository boardMemberRepository,
            final ObjectMapper objectMapper,
            final MeterRegistry meterRegistry,
            final WhiteboardPresenceRegistry presenceRegistry,
            final ParticipantsBroadcastService participantsBroadcastService) {
        this.messagingTemplate = messagingTemplate;
        this.canvasEventRepository = canvasEventRepository;
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
        CanvasEventType eventType;
        try {
            eventType = CanvasEventType.valueOf(message.type().toUpperCase());
        } catch (IllegalArgumentException e) {
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
        switch (eventType) {
            case JOIN -> handleJoin(boardId, message, principal, sessionId);
            case LEAVE -> handleLeave(boardId, principal, sessionId);
            case DRAW -> handleDraw(boardId, message, principal);
            case CURSOR_MOVE -> handleCursorMove(boardId, message, principal);
            case UNDO -> handleUndo(boardId, message, principal);
        }
    }

    // -------------------------------------------------------------------------
    // Private handlers
    // -------------------------------------------------------------------------

    /**
     * Handles a JOIN action: assigns colour, stores participant metadata, registers the
     * session in the presence liveness registry, broadcasts the JOIN event and emits
     * PARTICIPANTS_UPDATE.
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
        Map<String, Object> data = message.data() != null ? message.data() : Map.of();
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
        Map<String, Object> data = message.data() != null ? message.data() : Map.of();
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
        Map<String, Object> data = message.data() != null ? message.data() : Map.of();
        broadcast(boardId, principal, CanvasEventType.CURSOR_MOVE, data);
    }

    /**
     * Handles an UNDO action: broadcasts for visual synchronisation; stack logic is
     * delegated to US08.3.3.
     */
    private void handleUndo(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = message.data() != null ? message.data() : Map.of();
        broadcast(boardId, principal, CanvasEventType.UNDO, data);
        LOG.debug("Canvas UNDO broadcast: board={} user={}", boardId, principal.userId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Broadcasts a canvas event to all subscribers of the board's main topic.
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
        String destination = BOARD_TOPIC_PREFIX + boardId;
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                type.name(), boardId.toString(), principal.userId().toString(), data);
        messagingTemplate.convertAndSend(destination, msg);
    }

    /**
     * Resolves the role name string for a user on a board, defaulting to {@code "VIEWER"}
     * when the membership record is not found.
     *
     * @param boardId the board UUID
     * @param userId  the user UUID
     * @return the role name (e.g. {@code "OWNER"}, {@code "EDITOR"}, {@code "VIEWER"})
     */
    private String resolveRoleName(final UUID boardId, final UUID userId) {
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(m -> m.getRole().name())
                .orElse(BoardRole.VIEWER.name());
    }

    /**
     * Checks whether the given user has the VIEWER role on the board.
     *
     * @param boardId the board UUID
     * @param userId  the user UUID
     * @return {@code true} if the user is a VIEWER (or membership not found)
     */
    private boolean isViewer(final UUID boardId, final UUID userId) {
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
}
