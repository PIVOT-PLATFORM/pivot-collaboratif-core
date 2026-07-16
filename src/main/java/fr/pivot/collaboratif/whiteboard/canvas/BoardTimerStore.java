package fr.pivot.collaboratif.whiteboard.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Redis-backed store for the whiteboard's shared facilitation timer (EN30 facilitation
 * features, mirroring the PouetPouet reference's {@code board:timer:{boardId}} key).
 *
 * <p>The timer is <strong>ephemeral</strong> — no database table. Each board has at most one
 * running timer, stored as a single Redis string keyed by {@code ws:timer:{boardId}} whose
 * value is the timer's end instant ({@code endsAt}, epoch milliseconds). The key is written
 * with a TTL equal to the remaining duration, so an expired timer disappears from Redis on its
 * own — no reaper thread and no stale rows survive a crash or a missed {@code timer:stop}.
 *
 * <p>Reads and writes only ever happen from a canvas action handler that has already passed the
 * board-membership check enforced upstream by
 * {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardChannelInterceptor}, so a {@link UUID}
 * board id reaching this store is always one the caller's tenant may access — the boardId is a
 * globally-unique UUID, so a bare {@code ws:timer:{boardId}} key cannot collide across tenants.
 */
@Component
public class BoardTimerStore {

    private static final Logger LOG = LoggerFactory.getLogger(BoardTimerStore.class);
    private static final String TIMER_KEY_PREFIX = "ws:timer:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the store.
     *
     * @param redisTemplate Redis client for the ephemeral timer state
     */
    public BoardTimerStore(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Starts (or restarts) the board's timer, storing its end instant with a TTL equal to the
     * time remaining until then. A non-positive remaining duration (an {@code endsAt} in the
     * past) is a no-op — nothing is stored.
     *
     * @param boardId       the board UUID
     * @param endsAtEpochMs the timer's end instant, epoch milliseconds
     */
    public void start(final UUID boardId, final long endsAtEpochMs) {
        long ttlMs = endsAtEpochMs - System.currentTimeMillis();
        if (ttlMs <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(key(boardId), Long.toString(endsAtEpochMs), Duration.ofMillis(ttlMs));
    }

    /**
     * Stops the board's timer, removing its Redis key. Idempotent — deleting an absent key is a
     * silent no-op.
     *
     * @param boardId the board UUID
     */
    public void stop(final UUID boardId) {
        redisTemplate.delete(key(boardId));
    }

    /**
     * Returns the board's currently-running timer end instant, if any. A key whose stored
     * instant is already in the past (or is unparseable) is treated as absent and eagerly
     * deleted, so this never returns a stale or expired value.
     *
     * @param boardId the board UUID
     * @return the timer's end instant (epoch milliseconds) if a timer is currently running,
     *         otherwise {@link OptionalLong#empty()}
     */
    public OptionalLong getActiveEndsAt(final UUID boardId) {
        String value = redisTemplate.opsForValue().get(key(boardId));
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            long endsAt = Long.parseLong(value);
            if (endsAt > System.currentTimeMillis()) {
                return OptionalLong.of(endsAt);
            }
        } catch (NumberFormatException e) {
            LOG.warn("Discarding unparseable timer value '{}' for board={}", value, boardId);
        }
        redisTemplate.delete(key(boardId));
        return OptionalLong.empty();
    }

    /**
     * Returns the Redis key for a board's timer.
     *
     * @param boardId the board UUID
     * @return the Redis key
     */
    private String key(final UUID boardId) {
        return TIMER_KEY_PREFIX + boardId;
    }
}
