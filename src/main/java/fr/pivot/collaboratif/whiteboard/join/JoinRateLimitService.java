package fr.pivot.collaboratif.whiteboard.join;

import fr.pivot.collaboratif.exception.TooManyRequestsException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed rate limiter for {@code POST /whiteboard/join}.
 *
 * <p>Two independent windows are enforced — either can independently trigger a 429:
 * <ul>
 *   <li>Per authenticated user: 10 attempts per hour</li>
 *   <li>Per client IP address: 10 attempts per hour</li>
 * </ul>
 *
 * <p>Keys expire automatically after 1 hour (TTL set on first increment).
 * The counters count all attempts (failed or successful) to prevent enumeration
 * via high-volume guessing of valid tokens.
 */
@Service
public class JoinRateLimitService {

    /** Maximum join attempts per window period per key dimension. */
    static final int MAX_ATTEMPTS = 10;

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final String PREFIX_USER = "rate:join:user:";
    private static final String PREFIX_IP = "rate:join:ip:";

    private final StringRedisTemplate redis;

    /**
     * Creates the rate limiter backed by the shared Redis instance.
     *
     * @param redis the string Redis template
     */
    public JoinRateLimitService(final StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Increments the attempt counters for both the user and the IP address,
     * throwing {@link TooManyRequestsException} if either counter exceeds the limit.
     *
     * <p>The check and increment are not atomic at the Redis level — this is intentional:
     * the slight over-admission that can occur under extreme race conditions is acceptable
     * for a rate limiter protecting an invitation flow (not a financial transaction).
     *
     * @param userId    the authenticated caller's {@code public.users.id}
     * @param clientIp  the client's IP address from the servlet request
     * @throws TooManyRequestsException if either per-user or per-IP limit is exceeded
     */
    public void checkAndIncrement(final Long userId, final String clientIp) {
        checkKey(PREFIX_USER + userId, "user");
        checkKey(PREFIX_IP + clientIp, "IP");
    }

    /**
     * Resets the attempt counter for a specific user.
     *
     * @param userId the authenticated caller's {@code public.users.id}
     */
    public void resetUser(final Long userId) {
        redis.delete(PREFIX_USER + userId);
    }

    /**
     * Resets all join rate-limit counters — for use in integration tests only.
     *
     * <p>Scans and deletes all keys matching {@code rate:join:*}, ensuring test
     * isolation when tests share a Redis container.
     */
    public void resetAll() {
        var keys = redis.keys("rate:join:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void checkKey(final String key, final String dimension) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        if (count != null && count > MAX_ATTEMPTS) {
            throw new TooManyRequestsException(
                    "Rate limit exceeded for " + dimension + ": max " + MAX_ATTEMPTS
                            + " join attempts per hour");
        }
    }
}
