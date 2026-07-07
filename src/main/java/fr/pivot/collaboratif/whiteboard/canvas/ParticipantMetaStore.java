package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-backed store for canvas participant metadata (display name, avatar, colour, role).
 *
 * <p>Uses a Redis HASH keyed by {@code board:participant-meta:{tenantId}:{boardId}}
 * where each field is a userId (string) and the value is a JSON-serialised
 * {@link ParticipantInfo}. This structure allows O(1) reads/writes per participant and
 * an efficient scan of all participants for PARTICIPANTS_UPDATE broadcasts.
 *
 * <p>The store is populated at JOIN, cleaned up at explicit LEAVE, and also cleaned on
 * WebSocket disconnect via
 * {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardPresenceRegistry#leaveAll}.
 */
@Component
public class ParticipantMetaStore {

    private static final Logger LOG = LoggerFactory.getLogger(ParticipantMetaStore.class);
    private static final String META_KEY_PREFIX = "board:participant-meta:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the store.
     *
     * @param redisTemplate Redis client for participant metadata
     * @param objectMapper  Jackson 3 mapper for JSON serialisation/deserialisation
     */
    public ParticipantMetaStore(
            final StringRedisTemplate redisTemplate,
            final ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores a participant's metadata in the board's Redis hash.
     *
     * @param tenantId tenant UUID
     * @param boardId  board UUID
     * @param info     the participant info to store
     */
    public void put(final UUID tenantId, final UUID boardId, final ParticipantInfo info) {
        try {
            String json = objectMapper.writeValueAsString(info);
            redisTemplate.opsForHash().put(metaKey(tenantId, boardId), info.userId(), json);
        } catch (Exception e) {
            LOG.warn("Failed to serialise participant meta for user={} board={}: {}",
                    info.userId(), boardId, e.getMessage());
        }
    }

    /**
     * Removes a participant's metadata from the board's Redis hash.
     *
     * @param tenantId tenant UUID
     * @param boardId  board UUID
     * @param userId   the participant to remove
     */
    public void remove(final UUID tenantId, final UUID boardId, final UUID userId) {
        redisTemplate.opsForHash().delete(metaKey(tenantId, boardId), userId.toString());
    }

    /**
     * Returns all participants currently stored for the given board.
     *
     * @param tenantId tenant UUID
     * @param boardId  board UUID
     * @return list of participant infos; empty if none present
     */
    public List<ParticipantInfo> getAll(final UUID tenantId, final UUID boardId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(metaKey(tenantId, boardId));
        List<ParticipantInfo> result = new ArrayList<>();
        for (Object value : entries.values()) {
            try {
                ParticipantInfo info = objectMapper.readValue(value.toString(), ParticipantInfo.class);
                result.add(info);
            } catch (Exception e) {
                LOG.warn("Failed to deserialise participant meta entry '{}': {}", value, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Returns the Redis HASH key for a board's participant metadata.
     *
     * @param tenantId tenant UUID
     * @param boardId  board UUID
     * @return the Redis key
     */
    private String metaKey(final UUID tenantId, final UUID boardId) {
        return META_KEY_PREFIX + tenantId + ":" + boardId;
    }
}
