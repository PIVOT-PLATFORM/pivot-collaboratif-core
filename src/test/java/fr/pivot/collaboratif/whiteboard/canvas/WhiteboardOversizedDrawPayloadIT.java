package fr.pivot.collaboratif.whiteboard.canvas;

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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the US08.3.1 oversized-payload AC: "Payload DRAW limité à 64 Ko. Payload
 * &gt; limite → STOMP ERROR frame sans déconnecter les autres participants."
 *
 * <p>The 64 KB frame limit itself is configured in {@code WebSocketConfig}
 * ({@code registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT)}) and enforced by Spring's own
 * STOMP frame decoder before a message ever reaches {@code WhiteboardChannelInterceptor} — no
 * application code change was needed for the limit itself. What was missing (the Gate 4 audit
 * finding on PR #28) was any test proving the AC's actual observable behavior: that exceeding it
 * yields a STOMP ERROR frame rather than a silent drop or a hard disconnect, and — crucially —
 * that it does not take down other participants' sessions on the same board.
 *
 * <p>The WebSocket handshake itself is anonymous; authentication happens on the STOMP
 * {@code CONNECT} frame's native {@code Authorization} header, validated by {@code
 * StompAuthenticationChannelInterceptor} (EN08.3). Tenants/users/tokens are seeded through {@link PlatformAuthTestSupport} — the
 * {@code public.tenants}/{@code public.users} rows must exist before board/board-member rows are
 * inserted since {@code board.tenant_id}/{@code owner_id} and {@code board_member.user_id} now
 * carry FK constraints into those tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardOversizedDrawPayloadIT {

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
     * Given two members connected to the same board, when one sends a DRAW payload larger than
     * the 64 KB frame limit, then that sender receives a STOMP ERROR frame and the other
     * participant's session is neither disconnected nor otherwise disrupted — it keeps working,
     * proven by still receiving a subsequent, normal-sized broadcast (including one the
     * bystander sends itself, exercising both its inbound and outbound path after the incident).
     */
    @Test
    void oversizedDrawPayloadYieldsStompErrorWithoutDisconnectingOtherParticipants() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long otherId = seedUser(tenantId);
        String ownerToken = issueToken(ownerId);
        String otherToken = issueToken(otherId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), otherId), BoardRole.EDITOR, Instant.now()));

        CompletableFuture<String> errorFuture = new CompletableFuture<>();
        StompSession sender = connectWithFrameCapture(ownerToken, errorFuture);

        StompSession bystander = connectWithFrameCapture(otherToken, new CompletableFuture<>());
        CompletableFuture<BroadcastCanvasMessage> bystanderBroadcast = new CompletableFuture<>();
        bystander.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, bystanderBroadcast));

        // Brief pause so SimpleBroker registers the bystander's subscription before it matters.
        Thread.sleep(150);

        // Oversized payload: well over the 64 KB (65 536 byte) frame limit once serialized.
        String oversizedBlob = "A".repeat(70_000);
        sender.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW",
                        "data", Map.of("type", "stroke", "tool", "pencil",
                                "payload", Map.of("blob", oversizedBlob))));

        String error = errorFuture.get(8, TimeUnit.SECONDS);
        assertThat(error).isNotNull();

        // Other participant unaffected: its own session keeps working end-to-end.
        assertThat(bystander.isConnected())
                .as("other participants must not be disconnected by another session's "
                        + "oversized payload")
                .isTrue();
        bystander.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW",
                        "data", Map.of("type", "stroke", "tool", "pencil",
                                "payload", Map.of("color", "#00FF00"))));

        BroadcastCanvasMessage msg = bystanderBroadcast.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("DRAW");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Establishes a STOMP connection, authenticated with the given bearer token on the STOMP
     * {@code CONNECT} frame's native {@code Authorization} header, whose top-level session
     * handler captures any STOMP ERROR frame sent to it (ERROR frames are delivered to the
     * connection-level handler, never to a per-destination subscription handler).
     *
     * @param rawToken    the raw bearer token to send as {@code Authorization: Bearer <token>}
     *                    on the CONNECT frame
     * @param errorFuture completed with the ERROR frame body the first time one is received
     * @return an open STOMP session
     */
    private StompSession connectWithFrameCapture(
            final String rawToken, final CompletableFuture<String> errorFuture) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + rawToken);

        String url = "ws://localhost:" + port + "/api/collaboratif/ws/whiteboard";
        StompSession session = client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                if (!errorFuture.isDone()) {
                    errorFuture.complete(payload == null ? "" : payload.toString());
                }
            }
        }).get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Creates a board owned by {@code ownerId} within {@code tenantId} and saves it directly via
     * the JPA repositories.
     *
     * @param tenantId the tenant's {@code public.tenants.id}
     * @param ownerId  the owner's {@code public.users.id}
     * @return the persisted board
     */
    private Board createBoardWithOwner(final long tenantId, final long ownerId) {
        Board board = new Board("Test board", tenantId, ownerId, Instant.now());
        boardRepository.save(board);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, Instant.now()));
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
     * Returns a {@link StompFrameHandler} that completes the given future with the received
     * payload.
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
                future.complete(type.cast(payload));
            }
        };
    }
}
