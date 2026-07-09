package fr.pivot.collaboratif.whiteboard.ws;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WhiteboardChannelInterceptor}'s rate-limit strike enforcement.
 *
 * <p>Covers the US08.3.1 AC: "Rate limit par connexion WS : maximum 30 messages DRAW/seconde
 * par user par board. Dépassement → STOMP ERROR + fermeture après 3 violations consécutives" —
 * neither the strike counter, nor the enforced closure, nor the reset trigger had any test
 * coverage before this class (Gate 4 audit finding on PR #28 / US08.3.1: the counter and the
 * {@code messages.throttled.total} metric existed, but nothing proved the "fermeture" — closure —
 * half of the AC actually happened, and indeed it did not — see
 * {@link #thirdConsecutiveViolationForceClosesTheSession()}).
 *
 * <p>Redis is mocked with a real-INCR-like stub (a plain counter per key, distinguishing the
 * rate-limit key prefix {@code ws:rate:} from the strike key prefix {@code ws:strikes:}) so the
 * strike/consecutive-violation state machine can be driven precisely and deterministically,
 * without depending on the real 1-second fixed window's timing. The corresponding end-to-end
 * behavior against a real Redis instance and a real WebSocket transport — including the actual
 * forced disconnection — is covered by {@code WhiteboardRateLimitEnforcementIT}.
 */
class WhiteboardChannelInterceptorTest {

    private static final Long TENANT_ID = 100L;
    private static final UUID BOARD_ID = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-1";
    private static final String DESTINATION = "/app/whiteboard/" + BOARD_ID + "/action";
    private static final String STRIKES_KEY =
            "ws:strikes:" + SESSION_ID + ":" + TENANT_ID + ":" + BOARD_ID + ":" + USER_ID;

    private MembershipCacheService membershipCacheService;
    private StringRedisTemplate redisTemplate;
    private WhiteboardSessionRegistry sessionRegistry;
    private SimpMessagingTemplate messagingTemplate;
    private WhiteboardChannelInterceptor interceptor;

    /** Fixed-window rate counter for the single (tenant, board, user) triple under test. */
    private AtomicLong rateCounter;

    /** Sets up a fresh interceptor with mocked collaborators before each test. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        membershipCacheService = mock(MembershipCacheService.class);
        when(membershipCacheService.isMember(TENANT_ID, BOARD_ID, USER_ID)).thenReturn(true);

        redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        rateCounter = new AtomicLong(0);
        AtomicLong strikeCounter = new AtomicLong(0);
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.startsWith("ws:rate:")) {
                return rateCounter.incrementAndGet();
            }
            return strikeCounter.incrementAndGet();
        });
        // A real resetStrikes() deletes the key; mirror that on the in-memory strike counter so
        // the "reset actually took effect" test can observe strikes restarting from zero.
        when(redisTemplate.delete(STRIKES_KEY)).thenAnswer(invocation -> {
            strikeCounter.set(0);
            return true;
        });

        sessionRegistry = mock(WhiteboardSessionRegistry.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        interceptor = new WhiteboardChannelInterceptor(
                membershipCacheService, redisTemplate, new SimpleMeterRegistry(), sessionRegistry);
        ReflectionTestUtils.setField(interceptor, "messagingTemplate", messagingTemplate);
    }

    /**
     * Given a user who exceeds the per-second DRAW rate limit on three consecutive SEND
     * frames, when the third violation is processed, then the session is force-closed via
     * {@link WhiteboardSessionRegistry#close} with {@link CloseStatus#POLICY_VIOLATION} —
     * not merely warned. This is the AC's "fermeture après 3 violations consécutives"; before
     * this fix, the strike counter reached {@code MAX_STRIKES} and the client was told
     * "Session closed..." but no closure ever actually happened (a no-op enforcement bug
     * flagged by the Gate 4 audit on PR #28).
     */
    @Test
    void thirdConsecutiveViolationForceClosesTheSession() {
        primeRateLimitOverThreshold();

        sendFrame();
        sendFrame();
        verify(sessionRegistry, never()).close(anyString(), any());

        sendFrame();

        // The actual close is scheduled with a short grace delay (see
        // WhiteboardChannelInterceptor.CLOSE_GRACE_DELAY_MILLIS) so the closure notification
        // above has a chance to actually reach the client before the transport is torn down —
        // verify with a timeout rather than asserting the interaction happened synchronously.
        verify(sessionRegistry, timeout(1000).times(1))
                .close(SESSION_ID, CloseStatus.POLICY_VIOLATION);
    }

    /**
     * Given the first and second consecutive violations, when each is processed, then the
     * client is warned with the running strike count ("1/3", "2/3") rather than being told the
     * session was closed, and the session is not actually closed.
     */
    @Test
    void firstAndSecondViolationsWarnWithoutClosing() {
        primeRateLimitOverThreshold();

        sendFrame();
        sendFrame();

        ArgumentCaptor<ErrorPayload> payloadCaptor = ArgumentCaptor.forClass(ErrorPayload.class);
        verify(messagingTemplate, times(2))
                .convertAndSendToUser(eq(USER_ID.toString()), eq("/queue/errors"), payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues().get(0).error()).contains("1/3");
        assertThat(payloadCaptor.getAllValues().get(1).error()).contains("2/3");
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    /**
     * Given one rate-limit violation followed by a normal, within-limit message, when the
     * within-limit message is processed, then the strike counter is reset (the strike Redis key
     * is deleted) so that two further consecutive violations afterward do NOT reach the
     * third-strike threshold — proving the reset actually restarts the consecutive-violation
     * count rather than merely being attempted.
     */
    @Test
    void acceptedMessageBetweenViolationsResetsTheStrikeCounter() {
        primeRateLimitOverThreshold();
        sendFrame();
        verify(redisTemplate, never()).delete(STRIKES_KEY);

        // Drop back under the limit and send one accepted (allowed) message.
        rateCounter.set(0);
        sendFrame();
        verify(redisTemplate, times(1)).delete(STRIKES_KEY);

        // Two more violations after the reset must be strikes 1 and 2 of a NEW run, not 2 and 3
        // of the original one.
        rateCounter.set(30);
        sendFrame();
        sendFrame();
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    /**
     * Given a rate-limited SEND frame, when it is processed, then the
     * {@code messages.throttled.total} Micrometer counter is incremented — this metric existed
     * since PR #28 but, like the strike logic, had no test asserting it actually increments.
     */
    @Test
    void rateLimitedFrameIncrementsThrottledCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WhiteboardChannelInterceptor withRealRegistry = new WhiteboardChannelInterceptor(
                membershipCacheService, redisTemplate, registry, sessionRegistry);
        ReflectionTestUtils.setField(withRealRegistry, "messagingTemplate", messagingTemplate);
        rateCounter.set(30);

        withRealRegistry.preSend(buildSendMessage(), mock(MessageChannel.class));

        assertThat(registry.get("messages.throttled.total").counter().count()).isEqualTo(1.0d);
    }

    /**
     * Given a SEND frame within the rate limit, when it is processed, then the message passes
     * through unchanged (not dropped) and the heartbeat cache is refreshed.
     */
    @Test
    void withinLimitFrameIsAllowedThroughAndRefreshesHeartbeat() {
        Message<?> result = interceptor.preSend(buildSendMessage(), mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(membershipCacheService).refreshHeartbeat(TENANT_ID, BOARD_ID, USER_ID);
        verify(sessionRegistry, never()).close(anyString(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Sets the mocked rate counter so that the very next SEND on this (tenant, board, user)
     * triple is already over {@code MAX_MESSAGES_PER_SECOND} (30).
     */
    private void primeRateLimitOverThreshold() {
        rateCounter.set(30);
    }

    private void sendFrame() {
        interceptor.preSend(buildSendMessage(), mock(MessageChannel.class));
    }

    /**
     * Builds a STOMP SEND frame targeting the test board's action destination, authenticated
     * as the test user/tenant.
     *
     * @return the constructed STOMP SEND message
     */
    private Message<byte[]> buildSendMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination(DESTINATION);
        accessor.setUser(new StompPrincipal(USER_ID, TENANT_ID));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
