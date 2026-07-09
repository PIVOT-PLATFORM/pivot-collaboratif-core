package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the US08.3.1 rate-limit AC against a real Redis instance and a
 * real WebSocket transport: "Rate limit par connexion WS : maximum 30 messages DRAW/seconde par
 * user par board. Dépassement → STOMP ERROR + fermeture après 3 violations consécutives".
 *
 * <p>Before this fix, {@code WhiteboardChannelInterceptor} counted consecutive violations and
 * told the client "Session closed after repeated rate limit violations" on the third one, but
 * never actually closed anything — the session stayed fully open and usable. This test proves
 * the session is now genuinely terminated by the server, not just verbally warned. Deterministic
 * unit-level coverage of the strike counter's state machine lives in
 * {@code WhiteboardChannelInterceptorTest}; this class is the real-transport, real-Redis
 * counterpart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardRateLimitEnforcementIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context and seeds the
     * {@code public} schema (owned by {@code pivot-core}) before the Spring context and its
     * Flyway run start.
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

    private final List<StompSession> openSessions = new ArrayList<>();

    /** Disconnects all open STOMP sessions after each test. */
    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
    }

    /**
     * Given a board member sending DRAW actions well above the 30 messages/second limit, when
     * three consecutive frames are rate-limited, then the client receives an application-level
     * closure notification on {@code /user/queue/errors} (the same channel
     * {@code WhiteboardChannelInterceptor} uses for every denied/rate-limited frame — this is
     * not a raw STOMP ERROR command, see {@code sendError}) and the underlying connection is
     * actually torn down by the server — {@link StompSession#isConnected()} becomes
     * {@code false} on its own, without the client ever calling {@code disconnect()}.
     */
    @Test
    void threeConsecutiveRateLimitViolationsActuallyCloseTheConnection() throws Exception {
        long tenantId = PlatformAuthTestSupport.seedTenant(jdbcUrl(), dbUser(), dbPassword(), null);
        long ownerId = PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), tenantId, true);
        String token = PlatformAuthTestSupport.issueToken(
                jdbcUrl(), dbUser(), dbPassword(), ownerId, "active", Instant.now().plusSeconds(3600));
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<String> closureErrorFuture = new CompletableFuture<>();
        session.subscribe("/user/queue/errors", new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) payload;
                String error = String.valueOf(body.get("error"));
                if (error.toLowerCase(Locale.ROOT).contains("closed") && !closureErrorFuture.isDone()) {
                    closureErrorFuture.complete(error);
                }
            }
        });

        // Brief pause so SimpleBroker registers the /user/queue/errors subscription before the
        // flood starts — otherwise early warnings could be dropped as unsubscribed traffic.
        Thread.sleep(200);

        // Flood well past the 30 msg/s limit in one burst — comfortably within the 1-second
        // fixed window, and comfortably past the 3rd consecutive violation needed to trigger
        // forced closure.
        for (int i = 0; i < 40 && session.isConnected(); i++) {
            session.send("/app/whiteboard/" + board.getId() + "/action",
                    Map.of("type", "DRAW",
                            "data", Map.of("type", "stroke", "tool", "pencil",
                                    "payload", Map.of("i", i))));
        }

        String closureError = closureErrorFuture.get(8, TimeUnit.SECONDS);
        assertThat(closureError).containsIgnoringCase("closed");

        // The server-initiated close is asynchronous relative to the last SEND; poll briefly
        // rather than asserting immediately.
        long deadline = System.currentTimeMillis() + 5000;
        while (session.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(session.isConnected())
                .as("server must actually close the connection after 3 consecutive strikes, "
                        + "not merely announce it")
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Establishes a STOMP connection authenticated via the given bearer token, sent as
     * {@code Authorization: Bearer <token>} on the handshake HTTP request (EN08.3).
     *
     * @param rawToken the raw bearer token
     * @return an open STOMP session
     */
    private StompSession connectAs(final String rawToken) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + rawToken);

        String url = "ws://localhost:" + port + "/api/collaboratif/ws/whiteboard";
        StompSession session = client.connectAsync(url, httpHeaders, new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Creates a board owned by {@code ownerId} within {@code tenantId} and saves it directly via
     * the JPA repositories.
     *
     * @param tenantId the owning tenant's {@code public.tenants.id}
     * @param ownerId  the owning user's {@code public.users.id}
     * @return the persisted board
     */
    private Board createBoardWithOwner(final long tenantId, final long ownerId) {
        Board board = new Board("Test board", tenantId, ownerId, Instant.now());
        boardRepository.save(board);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, Instant.now()));
        return board;
    }

    private String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    private String dbUser() {
        return postgres.getUsername();
    }

    private String dbPassword() {
        return postgres.getPassword();
    }
}
