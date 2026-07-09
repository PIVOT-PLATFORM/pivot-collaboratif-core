package fr.pivot.collaboratif.whiteboard.ws;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.security.Principal;
import java.time.Duration;
import java.util.UUID;

/**
 * STOMP channel interceptor that enforces board access control on every inbound frame.
 *
 * <p>Intercepts two STOMP commands:
 * <ul>
 *   <li><b>SUBSCRIBE</b> — verifies the caller is a member of the board before allowing
 *       a subscription to {@code /topic/whiteboard/{boardId}} or
 *       {@code /topic/whiteboard/{boardId}/presence}. Denied requests are dropped (the
 *       subscription is never established) and an error notification is sent to the
 *       session's {@code /user/queue/errors} destination. The WebSocket session is
 *       <em>not</em> closed; other active subscriptions remain valid.</li>
 *   <li><b>SEND</b> — verifies membership for any message destined at
 *       {@code /app/whiteboard/{boardId}/...} and enforces a rate limit of
 *       {@value #MAX_MESSAGES_PER_SECOND} messages per second per (tenantId, boardId,
 *       userId) combination using a fixed-window Redis counter. Accepted frames also
 *       refresh the heartbeat cache entry (TTL 5 min) used for liveness tracking. Every
 *       rate-limited frame increments a per-session strike counter; on the
 *       {@value #MAX_STRIKES}th <em>consecutive</em> violation the session is not just warned —
 *       it is actually closed via {@link WhiteboardSessionRegistry#close} (the closure
 *       notification is delivered to {@code /user/queue/errors} first, then the underlying
 *       WebSocket transport connection is terminated), matching the US08.3.1 AC "fermeture après
 *       3 violations consécutives".</li>
 * </ul>
 *
 * <p>Both checks use {@link MembershipCacheService} which caches results in Redis with
 * a 5-second TTL, satisfying the EN08.1 revocation SLA.
 *
 * <p>{@link SimpMessagingTemplate} is injected lazily to break the circular dependency
 * that would arise because this interceptor is registered on the client inbound channel
 * during Spring's message broker configuration phase, before the messaging template is
 * fully initialised.
 */
@Component
public class WhiteboardChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteboardChannelInterceptor.class);

    /** Prefix for topic destinations that represent board rooms. */
    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    /** Prefix for application destinations that target board rooms. */
    private static final String BOARD_APP_PREFIX = "/app/whiteboard/";

    /** Redis key prefix for per-user per-board rate-limit counters. */
    private static final String RATE_KEY_PREFIX = "ws:rate:";

    /** Redis key prefix for per-session consecutive rate-limit violation strike counters. */
    private static final String STRIKES_KEY_PREFIX = "ws:strikes:";

    /** Maximum allowed STOMP SEND frames per user per board per second. */
    private static final int MAX_MESSAGES_PER_SECOND = 30;

    /** Number of consecutive rate-limit violations before effective session close. */
    private static final int MAX_STRIKES = 3;

    /** Window duration for the rate-limit counter (1 second fixed window). */
    private static final Duration RATE_WINDOW = Duration.ofSeconds(1);

    /** Strike counter TTL — resets automatically after 60 s of clean traffic. */
    private static final Duration STRIKES_TTL = Duration.ofSeconds(60);

    private final MembershipCacheService membershipCacheService;
    private final StringRedisTemplate redisTemplate;
    private final Counter throttledCounter;
    private final WhiteboardSessionRegistry sessionRegistry;

    /**
     * Messaging template used to deliver error notifications to denied sessions.
     * Lazily injected to avoid a circular dependency during broker configuration.
     */
    @Lazy
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the interceptor with the required dependencies.
     *
     * @param membershipCacheService Redis+DB membership cache for authorization decisions
     * @param redisTemplate          Redis client for rate-limit counters
     * @param meterRegistry          Micrometer registry for the throttled-messages counter
     * @param sessionRegistry        registry used to force-close a session after
     *                               {@value #MAX_STRIKES} consecutive rate-limit violations
     */
    public WhiteboardChannelInterceptor(
            final MembershipCacheService membershipCacheService,
            final StringRedisTemplate redisTemplate,
            final MeterRegistry meterRegistry,
            final WhiteboardSessionRegistry sessionRegistry) {
        this.membershipCacheService = membershipCacheService;
        this.redisTemplate = redisTemplate;
        this.throttledCounter = Counter.builder("messages.throttled.total")
                .description("Total STOMP canvas messages dropped due to rate limiting")
                .register(meterRegistry);
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Intercepts inbound STOMP frames, enforcing board membership and rate limits.
     *
     * @param message the inbound STOMP message
     * @param channel the inbound channel
     * @return the message if the frame is allowed, {@code null} to silently drop it
     */
    @Override
    public Message<?> preSend(final Message<?> message, final MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        return switch (command) {
            case SUBSCRIBE -> handleSubscribe(message, accessor);
            case SEND -> handleSend(message, accessor);
            default -> message;
        };
    }

    /**
     * Authorises a SUBSCRIBE frame targeting a board topic.
     *
     * <p>Destinations that do not start with {@code /topic/whiteboard/} are passed
     * through unchanged. For board topics, membership is verified; if denied the frame
     * is dropped and an error notification is sent to the session.
     *
     * @param message  the SUBSCRIBE frame
     * @param accessor the mutable STOMP header accessor
     * @return the original message if allowed, {@code null} if denied
     */
    private Message<?> handleSubscribe(
            final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(BOARD_TOPIC_PREFIX)) {
            return message;
        }
        String boardIdStr = extractBoardId(destination, BOARD_TOPIC_PREFIX);
        if (boardIdStr == null) {
            return message;
        }
        UUID boardId = parseUuid(boardIdStr);
        if (boardId == null) {
            LOG.warn("SUBSCRIBE denied: unparseable boardId in destination={}", destination);
            return null;
        }
        StompPrincipal principal = resolvePrincipal(accessor);
        if (principal == null) {
            LOG.warn("SUBSCRIBE denied: no principal for destination={}", destination);
            sendError(accessor.getSessionId(), accessor.getUser(), "Unauthenticated");
            return null;
        }
        if (!membershipCacheService.isMember(principal.tenantId(), boardId, principal.userId())) {
            LOG.warn("SUBSCRIBE denied: user={} tenant={} not a member of board={}",
                    principal.userId(), principal.tenantId(), boardId);
            sendError(accessor.getSessionId(), accessor.getUser(), "Access denied to board " + boardId);
            return null;
        }
        return message;
    }

    /**
     * Authorises a SEND frame targeting a board application destination and enforces
     * the per-user rate limit.
     *
     * @param message  the SEND frame
     * @param accessor the mutable STOMP header accessor
     * @return the original message if allowed, {@code null} if denied or rate-limited
     */
    private Message<?> handleSend(
            final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(BOARD_APP_PREFIX)) {
            return message;
        }
        String boardIdStr = extractBoardId(destination, BOARD_APP_PREFIX);
        if (boardIdStr == null) {
            return message;
        }
        UUID boardId = parseUuid(boardIdStr);
        if (boardId == null) {
            LOG.warn("SEND denied: unparseable boardId in destination={}", destination);
            return null;
        }
        StompPrincipal principal = resolvePrincipal(accessor);
        if (principal == null) {
            LOG.warn("SEND denied: no principal for destination={}", destination);
            return null;
        }
        if (!membershipCacheService.isMember(principal.tenantId(), boardId, principal.userId())) {
            LOG.warn("SEND denied: user={} tenant={} not a member of board={}",
                    principal.userId(), principal.tenantId(), boardId);
            sendError(accessor.getSessionId(), accessor.getUser(), "Access denied to board " + boardId);
            return null;
        }
        String sessionId = accessor.getSessionId();
        if (isRateLimited(principal.tenantId(), boardId, principal.userId())) {
            throttledCounter.increment();
            LOG.warn("SEND rate-limited: user={} board={}", principal.userId(), boardId);
            long strikes = incrementStrikes(sessionId, principal.tenantId(), boardId, principal.userId());
            if (strikes >= MAX_STRIKES) {
                resetStrikes(sessionId, principal.tenantId(), boardId, principal.userId());
                sendError(accessor.getSessionId(), accessor.getUser(),
                        "Session closed after repeated rate limit violations — please reconnect");
                sessionRegistry.close(sessionId, CloseStatus.POLICY_VIOLATION);
            } else {
                sendError(accessor.getSessionId(), accessor.getUser(),
                        "Rate limit exceeded (" + strikes + "/" + MAX_STRIKES + " violations)");
            }
            return null;
        }
        resetStrikes(sessionId, principal.tenantId(), boardId, principal.userId());
        membershipCacheService.refreshHeartbeat(principal.tenantId(), boardId, principal.userId());
        return message;
    }

    /**
     * Checks whether the given user has exceeded the per-second rate limit using a
     * fixed-window Redis INCR counter with a 1-second TTL.
     *
     * @param tenantId the user's tenant UUID
     * @param boardId  the board UUID
     * @param userId   the user UUID
     * @return {@code true} if the limit is exceeded; {@code false} otherwise
     */
    private boolean isRateLimited(final UUID tenantId, final UUID boardId, final UUID userId) {
        String key = RATE_KEY_PREFIX + tenantId + ":" + boardId + ":" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, RATE_WINDOW);
        }
        return count != null && count > MAX_MESSAGES_PER_SECOND;
    }

    /**
     * Increments the consecutive rate-limit violation strike counter for the session
     * and returns the current strike count.
     *
     * <p>The strike key has a 60-second TTL so that clean periods reset the counter
     * automatically. Three consecutive violations trigger effective session close (the
     * client receives an error and is expected to disconnect — see US08.3.1).
     *
     * @param sessionId the STOMP session ID (used as part of the key)
     * @param tenantId  the tenant UUID
     * @param boardId   the board UUID
     * @param userId    the user UUID
     * @return the current strike count after incrementing
     */
    private long incrementStrikes(
            final String sessionId, final UUID tenantId, final UUID boardId, final UUID userId) {
        String key = STRIKES_KEY_PREFIX + sessionId + ":" + tenantId + ":" + boardId + ":" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, STRIKES_TTL);
        }
        return count != null ? count : 1L;
    }

    /**
     * Resets the strike counter for the session after a successful (non-rate-limited) message.
     *
     * @param sessionId the STOMP session ID
     * @param tenantId  the tenant UUID
     * @param boardId   the board UUID
     * @param userId    the user UUID
     */
    private void resetStrikes(
            final String sessionId, final UUID tenantId, final UUID boardId, final UUID userId) {
        if (sessionId == null) {
            return;
        }
        String key = STRIKES_KEY_PREFIX + sessionId + ":" + tenantId + ":" + boardId + ":" + userId;
        redisTemplate.delete(key);
    }

    /**
     * Sends an error notification to the user's {@code /user/queue/errors} destination.
     * Failures to deliver the notification are logged at DEBUG and do not propagate.
     *
     * @param sessionId the STOMP session ID (for logging)
     * @param user      the session principal (for user-scoped routing)
     * @param error     the human-readable error reason
     */
    private void sendError(final String sessionId, final Principal user, final String error) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(user.getName(), "/queue/errors", new ErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification to session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Extracts the board UUID string from a STOMP destination, stripping the prefix and
     * any sub-path following the UUID segment.
     *
     * @param destination the full STOMP destination string
     * @param prefix      the prefix to strip (e.g. {@code /topic/whiteboard/})
     * @return the board UUID string if present; {@code null} if the destination is shorter
     *         than the prefix
     */
    private String extractBoardId(final String destination, final String prefix) {
        String after = destination.substring(prefix.length());
        if (after.isEmpty()) {
            return null;
        }
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }

    /**
     * Parses a UUID string, returning {@code null} on any parse failure.
     *
     * @param value the raw UUID string
     * @return the parsed {@link UUID} or {@code null}
     */
    private UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolves the {@link StompPrincipal} from the header accessor's user field.
     *
     * @param accessor the STOMP header accessor
     * @return the {@link StompPrincipal}, or {@code null} if the user is absent or
     *         of an unexpected type
     */
    private StompPrincipal resolvePrincipal(final StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal stompPrincipal) {
            return stompPrincipal;
        }
        return null;
    }
}
