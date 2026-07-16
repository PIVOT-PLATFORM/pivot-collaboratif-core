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
import java.util.Optional;
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
     * @param tenantId tenant's {@code public.tenants.id}
     * @param boardId  board UUID
     * @param info     the participant info to store
     */
    public void put(final Long tenantId, final UUID boardId, final ParticipantInfo info) {
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
     * @param tenantId tenant's {@code public.tenants.id}
     * @param boardId  board UUID
     * @param userId   the {@code public.users.id} of the participant to remove
     */
    public void remove(final Long tenantId, final UUID boardId, final Long userId) {
        redisTemplate.opsForHash().delete(metaKey(tenantId, boardId), userId.toString());
    }

    /**
     * Returns a single participant's stored metadata, if present — used to enrich high-frequency
     * ephemeral broadcasts (cursor moves, concurrent-editing signals) with the mover's display
     * name/avatar without scanning the whole board hash.
     *
     * @param tenantId tenant's {@code public.tenants.id}
     * @param boardId  board UUID
     * @param userId   the {@code public.users.id} of the participant to look up
     * @return the participant info, or empty if the user is not currently present or the entry
     *     could not be deserialised
     */
    public Optional<ParticipantInfo> get(final Long tenantId, final UUID boardId, final Long userId) {
        Object value = redisTemplate.opsForHash().get(metaKey(tenantId, boardId), userId.toString());
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value.toString(), ParticipantInfo.class));
        } catch (Exception e) {
            LOG.warn("Failed to deserialise participant meta for user={} board={}: {}", userId, boardId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns all participants currently stored for the given board.
     *
     * @param tenantId tenant's {@code public.tenants.id}
     * @param boardId  board UUID
     * @return list of participant infos; empty if none present
     */
    public List<ParticipantInfo> getAll(final Long tenantId, final UUID boardId) {
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
     * @param tenantId tenant's {@code public.tenants.id}
     * @param boardId  board UUID
     * @return the Redis key
     */
    private String metaKey(final Long tenantId, final UUID boardId) {
        return META_KEY_PREFIX + tenantId + ":" + boardId;
    }
}
