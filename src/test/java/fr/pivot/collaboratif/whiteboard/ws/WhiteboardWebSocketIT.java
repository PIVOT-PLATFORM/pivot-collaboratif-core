package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for EN08.1 — WebSocket room isolation per board.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A WebSocket connection without a bearer token is rejected (HTTP 401).</li>
 *   <li>A board member can subscribe to the board's STOMP topic and receives broadcasts
 *       sent to it.</li>
 *   <li>A non-member's SUBSCRIBE frame is silently dropped; they never receive a board
 *       broadcast.</li>
 *   <li>A user from a different tenant is blocked from subscribing to a board belonging
 *       to another tenant even when the boardId is known (cross-tenant isolation).</li>
 *   <li>A denied SUBSCRIBE does not close the WebSocket session; other subscriptions on
 *       the same session remain active.</li>
 * </ol>
 *
 * <p>Presence-specific scenarios (PARTICIPANTS_UPDATE on JOIN/LEAVE, multi-tab and
 * crash-without-LEAVE cleanup) are covered by {@link WhiteboardCanvasIT} (US08.3.1) and
 * {@code WhiteboardPresenceIT} (US08.5.1) — this class deliberately does not assert on
 * presence payloads so that room-isolation and presence-liveness concerns stay decoupled,
 * consistent with the collision fix in pivot-collaboratif-core#32 (a bare STOMP SUBSCRIBE no
 * longer represents "presence"; only an explicit JOIN application message does).
 *
 * <p>Uses {@link StandardWebSocketClient} (raw WebSocket, no SockJS) to connect to the
 * endpoint registered by {@link fr.pivot.collaboratif.config.WebSocketConfig}. The WebSocket
 * upgrade itself carries no identity (anonymous handshake, see {@link StompHandshakeHandler});
 * authentication happens on the first STOMP frame instead — the bearer token travels as a
 * native {@code Authorization} header on the STOMP {@code CONNECT} frame, validated by {@link
 * StompAuthenticationChannelInterceptor} (EN08.3). Tenants/users/tokens are seeded through {@link
 * PlatformAuthTestSupport} — the {@code public.tenants}/{@code public.users} rows must exist
 * before board/board-member rows are inserted since {@code board.tenant_id}/{@code owner_id}
 * and {@code board_member.user_id} now carry FK constraints into those tables. Board and
 * member records are created directly via JPA repositories to avoid HTTP layer coupling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardWebSocketIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context and seeds
     * the {@code public} schema (owned by {@code pivot-core}) before the Spring context and
     * its Flyway run start.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        PlatformAuthTestSupport.createPublicSchema(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private BoardMemberRepository boardMemberRepository;

    /** Keeps track of open sessions so they can be disconnected in teardown. */
    private final List<StompSession> openSessions = new ArrayList<>();

    /** Tears down all open WebSocket sessions after each test to prevent resource leaks. */
    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
    }

    // -------------------------------------------------------------------------
    // Test 1 — Handshake rejection
    // -------------------------------------------------------------------------

    /**
     * Given no Authorization header on the STOMP CONNECT frame,
     * when a connection is attempted,
     * then the server rejects the CONNECT and the client future completes exceptionally.
     */
    @Test
    void connect_without_bearer_token_is_rejected() {
        WebSocketStompClient client = createClient();
        CompletableFuture<StompSession> future = client.connectAsync(
                wsUrl(),
                new WebSocketHttpHeaders(),
                new StompSessionHandlerAdapter() {
                });

        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> future.get(5, TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // Test 2 — Member can subscribe and receives board broadcasts
    // -------------------------------------------------------------------------

    /**
     * Given a board with user A as OWNER,
     * when user A subscribes to {@code /topic/whiteboard/{boardId}} and sends a DRAW action,
     * then user A receives their own broadcast back (subscription is authorised and active).
     */
    @Test
    void board_member_can_subscribe_and_receives_broadcast() throws Exception {
        long tenantId = seedTenant();
        long userId = seedUser(tenantId);
        String token = issueToken(userId);
        Board board = createBoardWithOwner(tenantId, userId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> drawFuture = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, drawFuture));

        // Brief pause so SimpleBroker registers the subscription before the SEND below.
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW", "data", Map.of("type", "stroke")));

        BroadcastCanvasMessage msg = drawFuture.get(5, TimeUnit.SECONDS);
        assertThat(msg.userId()).isEqualTo(String.valueOf(userId));
    }

    // -------------------------------------------------------------------------
    // Test 3 — Non-member SUBSCRIBE is denied
    // -------------------------------------------------------------------------

    /**
     * Given a board where user B has no membership,
     * when user B subscribes to {@code /topic/whiteboard/{boardId}} and the owner then sends
     * a DRAW action,
     * then user B never receives the broadcast (the SUBSCRIBE was silently dropped).
     */
    @Test
    void non_member_subscribe_is_denied_and_never_receives_broadcast() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long nonMemberId = seedUser(tenantId);
        String ownerToken = issueToken(ownerId);
        String nonMemberToken = issueToken(nonMemberId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession nonMemberSession = connectAs(nonMemberToken);
        CompletableFuture<BroadcastCanvasMessage> drawFuture = new CompletableFuture<>();
        nonMemberSession.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, drawFuture));

        Thread.sleep(200);

        StompSession ownerSession = connectAs(ownerToken);
        ownerSession.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW", "data", Map.of("type", "stroke")));

        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> drawFuture.get(2, TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // Test 4 — Cross-tenant isolation
    // -------------------------------------------------------------------------

    /**
     * Given board B created in tenant T1,
     * when user from tenant T2 subscribes to {@code /topic/whiteboard/{B.id}},
     * then the subscription is denied and no broadcast is ever received.
     */
    @Test
    void cross_tenant_subscribe_is_denied() throws Exception {
        long tenantT1 = seedTenant();
        long ownerT1 = seedUser(tenantT1);
        Board board = createBoardWithOwner(tenantT1, ownerT1);

        long tenantT2 = seedTenant();
        long userT2 = seedUser(tenantT2);
        String userT2Token = issueToken(userT2);

        StompSession sessionT2 = connectAs(userT2Token);
        CompletableFuture<BroadcastCanvasMessage> drawFuture = new CompletableFuture<>();
        sessionT2.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, drawFuture));

        Thread.sleep(200);

        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> drawFuture.get(2, TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // Test 5 — Session not closed after denied SUBSCRIBE
    // -------------------------------------------------------------------------

    /**
     * Given user A is member of board 1 but not board 2,
     * when user A subscribes to board 2 (denied) then to board 1 (allowed),
     * then user A still receives broadcasts on board 1 (session remained active).
     */
    @Test
    void denied_subscribe_does_not_close_session() throws Exception {
        long tenantId = seedTenant();
        long userId = seedUser(tenantId);
        String token = issueToken(userId);
        Board board1 = createBoardWithOwner(tenantId, userId);
        long otherOwnerId = seedUser(tenantId);
        Board board2 = createBoardWithOwner(tenantId, otherOwnerId);

        StompSession session = connectAs(token);

        CompletableFuture<BroadcastCanvasMessage> board1DrawFuture = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board2.getId(), new NoopFrameHandler());
        session.subscribe("/topic/whiteboard/" + board1.getId(),
                framHandler(BroadcastCanvasMessage.class, board1DrawFuture));

        Thread.sleep(100);

        session.send("/app/whiteboard/" + board1.getId() + "/action",
                Map.of("type", "DRAW", "data", Map.of("type", "stroke")));

        BroadcastCanvasMessage msg = board1DrawFuture.get(5, TimeUnit.SECONDS);
        assertThat(msg.userId()).isEqualTo(String.valueOf(userId));
        assertThat(session.isConnected()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the WebSocket URL for the STOMP endpoint.
     *
     * @return the WebSocket URL including the context-path
     */
    private String wsUrl() {
        return "ws://localhost:" + port + "/api/collaboratif/ws/whiteboard";
    }

    /**
     * Creates a configured {@link WebSocketStompClient} using a raw WebSocket transport.
     *
     * @return a ready-to-use STOMP client
     */
    private WebSocketStompClient createClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        return client;
    }

    /**
     * Connects to the WebSocket endpoint using the given bearer token on the STOMP
     * {@code CONNECT} frame's native {@code Authorization} header, blocking until the STOMP
     * session is established or timing out after 5 seconds.
     *
     * @param rawToken the raw bearer token to send as {@code Authorization: Bearer <token>} on
     *                 the CONNECT frame
     * @return the established {@link StompSession}
     * @throws Exception if the connection fails or times out
     */
    private StompSession connectAs(final String rawToken) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + rawToken);
        StompSession session = createClient()
                .connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Creates a board owned by the given user within the given tenant and persists both
     * the board and the OWNER membership record.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param ownerId  the owning user's {@code public.users.id}
     * @return the persisted {@link Board}
     */
    private Board createBoardWithOwner(final long tenantId, final long ownerId) {
        Instant now = Instant.now();
        Board board = boardRepository.save(new Board("Test Board", tenantId, ownerId, now));
        boardMemberRepository.save(
                new BoardMember(new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, now));
        return board;
    }

    /**
     * Seeds a tenant row in {@code public.tenants}.
     *
     * @return the generated tenant id
     * @throws Exception if the insert fails
     */
    private long seedTenant() throws Exception {
        return PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
    }

    /**
     * Seeds an active user row in {@code public.users} belonging to the given tenant.
     *
     * @param tenantId the owning tenant's id
     * @return the generated user id
     * @throws Exception if the insert fails
     */
    private long seedUser(final long tenantId) throws Exception {
        return PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
    }

    /**
     * Issues a valid, non-expired {@code active} bearer token for the given user.
     *
     * @param userId the owning user's id
     * @return the raw bearer token
     * @throws Exception if the insert fails
     */
    private String issueToken(final long userId) throws Exception {
        return PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userId, "active", Instant.now().plusSeconds(3600));
    }

    /**
     * Returns a {@link StompFrameHandler} that completes the given future with the
     * received payload.
     *
     * @param type   the expected payload class
     * @param future the future to complete
     * @param <T>    the payload type
     * @return a frame handler
     */
    private <T> StompFrameHandler framHandler(final Class<T> type, final CompletableFuture<T> future) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                if (!future.isDone()) {
                    future.complete(type.cast(payload));
                }
            }
        };
    }

    /**
     * A no-op STOMP frame handler used when the test does not need to process
     * received frames.
     */
    private static final class NoopFrameHandler implements StompFrameHandler {

        /**
         * Returns {@link Object} as the payload type since frames are discarded.
         *
         * @param headers the STOMP headers (not used)
         * @return {@code Object.class}
         */
        @Override
        public Type getPayloadType(final StompHeaders headers) {
            return Object.class;
        }

        /**
         * Discards the received frame.
         *
         * @param headers the STOMP frame headers
         * @param payload the decoded payload
         */
        @Override
        public void handleFrame(final StompHeaders headers, final Object payload) {
            // intentionally empty — test does not inspect this frame
        }
    }
}
