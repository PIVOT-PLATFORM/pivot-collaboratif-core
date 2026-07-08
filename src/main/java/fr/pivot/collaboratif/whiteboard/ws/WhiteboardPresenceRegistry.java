package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.collaboratif.whiteboard.canvas.ParticipantMetaStore;
import fr.pivot.collaboratif.whiteboard.canvas.ParticipantsBroadcastService;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed session-liveness tracker for whiteboard board rooms.
 *
 * <p>Presence itself (the {@link ParticipantMetaStore} / {@link ParticipantsUpdatePayload}
 * broadcast) is owned exclusively by the application-level JOIN/LEAVE flow in
 * {@code CanvasActionService} (US08.3.1) — this class does <b>not</b> write to the presence
 * store and does not broadcast on JOIN or explicit LEAVE. Its sole responsibility is tracking
 * which STOMP sessions are currently open for a given {@code (tenantId, boardId, userId)}
 * triple, so that a WebSocket disconnect can tell whether it was the user's <em>last</em>
 * active session on that board before touching presence.
 *
 * <p>Before US08.5.1, this registry independently tracked subscriptions (on
 * {@code SessionSubscribeEvent}) and broadcast its own {@code PresencePayload} shape to the
 * same {@code /topic/whiteboard/{boardId}/presence} topic that {@code CanvasActionService}
 * broadcasts {@link ParticipantsUpdatePayload} to — two independent systems racing on the same
 * topic with incompatible payload shapes, and a single-session-per-user model that dropped a
 * user's presence entirely if just one of several open tabs crashed. This class now only
 * tracks liveness; see pivot-collaboratif-core#32 for the full incident analysis.
 *
 * <p>Two Redis data structures are maintained:
 * <ul>
 *   <li>SET {@code board:sessions:{tenantId}:{boardId}:{userId}} — every sessionId currently
 *       JOINed to that board room for that user. Size &gt; 1 means the same user has multiple
 *       tabs/sessions open on the same board (multi-tab support).</li>
 *   <li>SET {@code ws:session:{sessionId}} — the composite keys
 *       {@code {tenantId}:{boardId}:{userId}} that a given session has registered, enabling
 *       cleanup of every board room a session had joined without scanning all boards.</li>
 * </ul>
 *
 * <p>Both SETs are given a 24-hour TTL as a safeguard against orphaned entries in case of
 * abnormal server termination; normal cleanup happens via {@link #unregisterSession} (explicit
 * LEAVE) and {@link #handleDisconnect} (WebSocket disconnect, with or without a prior LEAVE —
 * including the 30 s silent-disconnect STOMP heartbeat timeout already configured in
 * {@code WebSocketConfig} for US08.3.1, which closes the session and fires
 * {@code SessionDisconnectEvent} without any new scheduled task needed here).
 */
@Component
public class WhiteboardPresenceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteboardPresenceRegistry.class);
    private static final String BOARD_SESSIONS_PREFIX = "board:sessions:";
    private static final String SESSION_KEY_PREFIX = "ws:session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ParticipantMetaStore participantMetaStore;
    private final ParticipantsBroadcastService participantsBroadcastService;

    /**
     * Creates the registry with the required infrastructure beans.
     *
     * @param redisTemplate                Redis client for session-liveness bookkeeping
     * @param participantMetaStore         store for canvas participant metadata, cleaned only
     *                                     when a disconnecting session was the user's last one
     *                                     on the board
     * @param participantsBroadcastService shared broadcaster, invoked only when a disconnect
     *                                     turns out to be the user's last active session on a
     *                                     board (no prior explicit LEAVE)
     */
    public WhiteboardPresenceRegistry(
            final StringRedisTemplate redisTemplate,
            final ParticipantMetaStore participantMetaStore,
            final ParticipantsBroadcastService participantsBroadcastService) {
        this.redisTemplate = redisTemplate;
        this.participantMetaStore = participantMetaStore;
        this.participantsBroadcastService = participantsBroadcastService;
    }

    /**
     * Registers a newly opened session as an active participant session for the given board
     * room. Called by {@code CanvasActionService} when an application-level JOIN is processed —
     * never on mere STOMP SUBSCRIBE.
     *
     * @param tenantId  the user's tenant UUID
     * @param boardId   the board UUID
     * @param userId    the joining user's UUID
     * @param sessionId the STOMP session ID of the connection
     */
    public void registerSession(
            final UUID tenantId, final UUID boardId, final UUID userId, final String sessionId) {
        String sessionsKey = boardSessionsKey(tenantId, boardId, userId);
        redisTemplate.opsForSet().add(sessionsKey, sessionId);
        redisTemplate.expire(sessionsKey, SESSION_TTL);

        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForSet().add(sessionKey, compositeKey(tenantId, boardId, userId));
        redisTemplate.expire(sessionKey, SESSION_TTL);

        LOG.debug("Session registered: board={} user={} tenant={} session={}", boardId, userId, tenantId, sessionId);
    }

    /**
     * Unregisters a session from a board room on an explicit application-level LEAVE. Called
     * by {@code CanvasActionService} <em>after</em> it has already unconditionally cleared the
     * participant from {@link ParticipantMetaStore} and broadcast the resulting
     * {@link ParticipantsUpdatePayload} — this method only keeps the liveness bookkeeping
     * consistent so a later disconnect of the same or another session does not double-count.
     *
     * @param tenantId  the user's tenant UUID
     * @param boardId   the board UUID
     * @param userId    the leaving user's UUID
     * @param sessionId the STOMP session ID that sent the LEAVE
     */
    public void unregisterSession(
            final UUID tenantId, final UUID boardId, final UUID userId, final String sessionId) {
        redisTemplate.opsForSet().remove(boardSessionsKey(tenantId, boardId, userId), sessionId);
        redisTemplate.opsForSet().remove(SESSION_KEY_PREFIX + sessionId, compositeKey(tenantId, boardId, userId));
        LOG.debug("Session unregistered (explicit LEAVE): board={} user={} tenant={} session={}",
                boardId, userId, tenantId, sessionId);
    }

    /**
     * Cleans up every board room a disconnecting session had joined. For each room, the
     * session is removed from the user's active-session set; only when that set becomes empty
     * (i.e. this was the user's <em>last</em> active session on that board) is the participant
     * actually removed from {@link ParticipantMetaStore} and a {@link ParticipantsUpdatePayload}
     * broadcast — this covers a crash without a prior LEAVE while leaving presence untouched
     * for a user who still has another tab/session open on the same board.
     *
     * @param sessionId the STOMP session ID to clean up
     */
    public void handleDisconnect(final String sessionId) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        Set<String> entries = redisTemplate.opsForSet().members(sessionKey);
        if (entries != null) {
            for (String entry : entries) {
                processDisconnectEntry(entry, sessionId);
            }
        }
        redisTemplate.delete(sessionKey);
    }

    /**
     * Parses a session-entry composite key, drops the session from that board's active-session
     * set, and — only if no active session remains for the user on that board — clears
     * presence metadata and broadcasts the updated participant list.
     *
     * @param entry     composite key {@code {tenantId}:{boardId}:{userId}}
     * @param sessionId the disconnecting session ID
     */
    private void processDisconnectEntry(final String entry, final String sessionId) {
        String[] parts = entry.split(":", 3);
        if (parts.length != 3) {
            LOG.debug("Skipping malformed session entry: {}", entry);
            return;
        }
        try {
            UUID tenantId = UUID.fromString(parts[0]);
            UUID boardId = UUID.fromString(parts[1]);
            UUID userId = UUID.fromString(parts[2]);
            String sessionsKey = boardSessionsKey(tenantId, boardId, userId);
            redisTemplate.opsForSet().remove(sessionsKey, sessionId);
            Long remaining = redisTemplate.opsForSet().size(sessionsKey);
            if (remaining == null || remaining == 0) {
                redisTemplate.delete(sessionsKey);
                participantMetaStore.remove(tenantId, boardId, userId);
                LOG.info("WebSocket disconnect (last session): board={} user={} tenant={} — presence cleared",
                        boardId, userId, tenantId);
                participantsBroadcastService.broadcast(tenantId, boardId);
            } else {
                LOG.debug("WebSocket disconnect: user={} still has {} active session(s) on board={} — presence kept",
                        userId, remaining, boardId);
            }
        } catch (IllegalArgumentException e) {
            LOG.debug("Skipping invalid session entry '{}': {}", entry, e.getMessage());
        }
    }

    /**
     * Returns the Redis SET key for a user's active sessions on a board.
     *
     * @param tenantId the tenant UUID
     * @param boardId  the board UUID
     * @param userId   the user UUID
     * @return the Redis key string
     */
    private String boardSessionsKey(final UUID tenantId, final UUID boardId, final UUID userId) {
        return BOARD_SESSIONS_PREFIX + tenantId + ":" + boardId + ":" + userId;
    }

    /**
     * Builds the composite key stored in a session's reverse-index SET.
     *
     * @param tenantId the tenant UUID
     * @param boardId  the board UUID
     * @param userId   the user UUID
     * @return the composite key {@code {tenantId}:{boardId}:{userId}}
     */
    private String compositeKey(final UUID tenantId, final UUID boardId, final UUID userId) {
        return tenantId + ":" + boardId + ":" + userId;
    }
}
