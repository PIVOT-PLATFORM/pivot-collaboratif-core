package fr.pivot.collaboratif.whiteboard.importer;

import fr.pivot.collaboratif.exception.TooManyRequestsException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed rate limiter for {@code POST /whiteboard/boards/{boardId}/import/klaxoon}
 * (US08.13.1).
 *
 * <p>Maximum 5 imports per rolling 1-minute window, <strong>per board</strong> — a 6th import
 * within the window is rejected with HTTP 429. Mirrors {@link
 * fr.pivot.collaboratif.whiteboard.join.JoinRateLimitService}'s fixed-window counter idiom
 * exactly (increment-then-check, TTL set on the first increment of a window).
 *
 * <p><strong>Permanent guard, active in every environment.</strong> This corrects the reference
 * POC finding (§6 constat 16), which only enabled its import rate limit in {@code NODE_ENV=
 * production} — here the limiter is wired unconditionally, with no profile-gated bypass.
 */
@Service
public class ImportRateLimitService {

    /** Maximum import attempts per board per rolling window. */
    static final int MAX_IMPORTS_PER_WINDOW = 5;

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String PREFIX = "rate:import:board:";

    private final StringRedisTemplate redis;

    /**
     * Creates the rate limiter backed by the shared Redis instance.
     *
     * @param redis the string Redis template
     */
    public ImportRateLimitService(final StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Increments the per-board attempt counter, throwing {@link TooManyRequestsException} once it
     * exceeds {@link #MAX_IMPORTS_PER_WINDOW} within the current rolling window.
     *
     * <p>Not atomic at the Redis level (increment, then a separate {@code EXPIRE} on the first hit
     * of a window) — the same accepted trade-off as {@code JoinRateLimitService}: a slight
     * over-admission under extreme race conditions is acceptable for an import quota, not a
     * financial transaction.
     *
     * @param boardId the board being imported into
     * @throws TooManyRequestsException if the board's import quota is exhausted for this window
     */
    public void checkAndIncrement(final UUID boardId) {
        String key = PREFIX + boardId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        if (count != null && count > MAX_IMPORTS_PER_WINDOW) {
            throw new TooManyRequestsException(
                    "Rate limit exceeded for board import: max " + MAX_IMPORTS_PER_WINDOW
                            + " imports per minute");
        }
    }

    /**
     * Resets every import rate-limit counter — for use in integration tests only, ensuring test
     * isolation when tests share a Redis container.
     */
    public void resetAll() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
