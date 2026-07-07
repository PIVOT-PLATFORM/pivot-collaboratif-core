package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.collaboratif.whiteboard.canvas.ParticipantMetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed presence registry for whiteboard board rooms.
 *
 * <p>Tracks which users are currently connected to a board's STOMP room and broadcasts
 * the updated participant list whenever the set changes. Two Redis data structures are
 * maintained per session:
 * <ul>
 *   <li>HASH {@code board:presence:{tenantId}:{boardId}} — maps each connected userId
 *       (string) to the sessionId that established the subscription. Doubly indexed so
 *       that presence events always carry both tenant and board context (EN08.1 requirement).</li>
 *   <li>SET {@code ws:session:{sessionId}} — stores the composite keys
 *       {@code {tenantId}:{boardId}:{userId}} for every board room the session has
 *       joined, enabling efficient cleanup on disconnect without scanning all boards.</li>
 * </ul>
 *
 * <p>The session SET is given a 24-hour TTL as a safeguard against orphaned entries in
 * case of abnormal server termination; normal cleanup happens via {@link #leaveAll}.
 */
@Component
public class WhiteboardPresenceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteboardPresenceRegistry.class);
    private static final String BOARD_PRESENCE_PREFIX = "board:presence:";
    private static final String SESSION_KEY_PREFIX = "ws:session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final String PRESENCE_TOPIC_PREFIX = "/topic/whiteboard/";
    private static final String PRESENCE_SUFFIX = "/presence";

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ParticipantMetaStore participantMetaStore;

    /**
     * Creates the registry with the required infrastructure beans.
     *
     * @param redisTemplate        Redis client for presence storage
     * @param messagingTemplate    STOMP messaging template for broadcasting presence updates;
     *                             injected lazily to avoid circular dependencies with the
     *                             message broker configuration
     * @param participantMetaStore store for canvas participant metadata, cleaned on disconnect
     */
    public WhiteboardPresenceRegistry(
            final StringRedisTemplate redisTemplate,
            @Lazy final SimpMessagingTemplate messagingTemplate,
            final ParticipantMetaStore participantMetaStore) {
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.participantMetaStore = participantMetaStore;
    }

    /**
     * Registers a user's subscription to a board room and broadcasts the updated
     * participant list to all subscribers of the board's presence topic.
     *
     * @param tenantId  the user's tenant UUID (required for tenant isolation)
     * @param boardId   the board UUID
     * @param userId    the connecting user's UUID
     * @param sessionId the STOMP session ID of the connection
     */
    public void join(
            final UUID tenantId,
            final UUID boardId,
            final UUID userId,
            final String sessionId) {
        String boardKey = boardPresenceKey(tenantId, boardId);
        redisTemplate.opsForHash().put(boardKey, userId.toString(), sessionId);

        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        String entry = tenantId + ":" + boardId + ":" + userId;
        redisTemplate.opsForSet().add(sessionKey, entry);
        redisTemplate.expire(sessionKey, SESSION_TTL);

        LOG.info("WebSocket JOIN board={} user={} tenant={} session={}", boardId, userId, tenantId, sessionId);
        broadcastPresence(tenantId, boardId);
    }

    /**
     * Removes a user from a specific board room and broadcasts the updated participant list.
     *
     * @param tenantId the user's tenant UUID
     * @param boardId  the board UUID
     * @param userId   the user's UUID
     */
    public void leave(final UUID tenantId, final UUID boardId, final UUID userId) {
        String boardKey = boardPresenceKey(tenantId, boardId);
        redisTemplate.opsForHash().delete(boardKey, userId.toString());
        LOG.info("WebSocket LEAVE board={} user={} tenant={}", boardId, userId, tenantId);
        broadcastPresence(tenantId, boardId);
    }

    /**
     * Removes the session from all board rooms it had joined and broadcasts presence
     * updates for each affected board. Called on WebSocket disconnect.
     *
     * @param sessionId the STOMP session ID to clean up
     */
    public void leaveAll(final String sessionId) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        Set<String> entries = redisTemplate.opsForSet().members(sessionKey);
        if (entries != null) {
            for (String entry : entries) {
                processLeaveEntry(entry);
            }
        }
        redisTemplate.delete(sessionKey);
    }

    /**
     * Returns the UUIDs (as strings) of all users currently connected to the board room.
     *
     * @param tenantId the tenant UUID
     * @param boardId  the board UUID
     * @return list of connected user IDs; empty if no users are present
     */
    public List<String> getPresent(final UUID tenantId, final UUID boardId) {
        String boardKey = boardPresenceKey(tenantId, boardId);
        Set<Object> keys = redisTemplate.opsForHash().keys(boardKey);
        List<String> result = new ArrayList<>();
        for (Object key : keys) {
            result.add(key.toString());
        }
        return result;
    }

    /**
     * Parses a session-entry composite key and removes the user from the board room.
     *
     * @param entry composite key {@code {tenantId}:{boardId}:{userId}}
     */
    private void processLeaveEntry(final String entry) {
        String[] parts = entry.split(":", 3);
        if (parts.length != 3) {
            LOG.debug("Skipping malformed presence entry: {}", entry);
            return;
        }
        try {
            UUID tenantId = UUID.fromString(parts[0]);
            UUID boardId = UUID.fromString(parts[1]);
            UUID userId = UUID.fromString(parts[2]);
            String boardKey = boardPresenceKey(tenantId, boardId);
            redisTemplate.opsForHash().delete(boardKey, userId.toString());
            participantMetaStore.remove(tenantId, boardId, userId);
            LOG.info("WebSocket LEAVE (disconnect) board={} user={} tenant={}", boardId, userId, tenantId);
            broadcastPresence(tenantId, boardId);
        } catch (IllegalArgumentException e) {
            LOG.debug("Skipping invalid presence entry '{}': {}", entry, e.getMessage());
        }
    }

    /**
     * Broadcasts the current presence list for a board to all subscribers of its presence topic.
     *
     * @param tenantId the tenant UUID
     * @param boardId  the board UUID
     */
    private void broadcastPresence(final UUID tenantId, final UUID boardId) {
        List<String> userIds = getPresent(tenantId, boardId);
        String destination = PRESENCE_TOPIC_PREFIX + boardId + PRESENCE_SUFFIX;
        messagingTemplate.convertAndSend(destination, new PresencePayload(userIds));
        LOG.debug("Presence broadcast {} participant(s) to {}", userIds.size(), destination);
    }

    /**
     * Returns the Redis HASH key for a board's presence data.
     *
     * @param tenantId the tenant UUID
     * @param boardId  the board UUID
     * @return the Redis key string
     */
    private String boardPresenceKey(final UUID tenantId, final UUID boardId) {
        return BOARD_PRESENCE_PREFIX + tenantId + ":" + boardId;
    }
}
