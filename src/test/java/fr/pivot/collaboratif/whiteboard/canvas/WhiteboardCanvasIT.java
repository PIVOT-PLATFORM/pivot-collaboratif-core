package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEvent;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.3.1 — WebSocket canvas connection and action handling.
 *
 * <p>Verifies:
 * <ol>
 *   <li>JOIN assigns a colour and emits PARTICIPANTS_UPDATE.</li>
 *   <li>DRAW action is broadcast to the board topic.</li>
 *   <li>DRAW event is persisted in the database.</li>
 *   <li>Unknown message type is silently dropped (no error, session intact).</li>
 *   <li>VIEWER cannot send UNDO (error delivered to {@code /user/queue/errors}).</li>
 *   <li>CURSOR_MOVE is broadcast but not persisted.</li>
 *   <li>LEAVE removes participant and emits updated PARTICIPANTS_UPDATE.</li>
 * </ol>
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
class WhiteboardCanvasIT {

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

    @Autowired
    private CanvasEventRepository canvasEventRepository;

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

    // =========================================================================
    // Test 1 — JOIN assigns colour and emits PARTICIPANTS_UPDATE
    // =========================================================================

    /**
     * When an OWNER sends JOIN, the server assigns a colour, stores participant metadata
     * and broadcasts PARTICIPANTS_UPDATE to the presence topic.
     *
     * <p>Note: we deliberately do NOT subscribe to the main board topic here. Subscribing
     * to {@code /topic/whiteboard/{boardId}} would trigger EN08.1's
     * {@code WhiteboardPresenceRegistry} to send a {@code PresencePayload} (with field
     * {@code userIds}) to the presence topic BEFORE the JOIN action's
     * {@code ParticipantsUpdatePayload} arrives — completing the future with the wrong
     * payload type. Since SEND frames are allowed for any board member regardless of
     * subscription, the main board subscription is not required for this test.
     */
    @Test
    void join_assigns_color_and_emits_participants_update() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);

        // Collect all messages on the presence topic and pick the first ParticipantsUpdatePayload
        List<ParticipantsUpdatePayload> updates = new ArrayList<>();
        session.subscribe("/topic/whiteboard/" + board.getId() + "/presence",
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(final StompHeaders headers) {
                        return ParticipantsUpdatePayload.class;
                    }

                    @Override
                    public void handleFrame(final StompHeaders headers, final Object payload) {
                        ParticipantsUpdatePayload p = (ParticipantsUpdatePayload) payload;
                        if (p.participants() != null && !p.participants().isEmpty()) {
                            synchronized (updates) {
                                updates.add(p);
                                updates.notifyAll();
                            }
                        }
                    }
                });

        // Brief pause to ensure the subscription is registered in SimpleBroker before sending.
        // STOMP subscription frames are processed asynchronously; without the pause there is
        // a small window where the broadcastParticipantsUpdate() message can be dispatched
        // before SimpleBroker has registered the subscriber, causing the message to be lost.
        Thread.sleep(100);

        // Send JOIN — no main board subscription needed for SEND
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "JOIN", "data", Map.of("displayName", "Alice")));

        synchronized (updates) {
            long deadline = System.currentTimeMillis() + 8000;
            while (updates.isEmpty() && System.currentTimeMillis() < deadline) {
                updates.wait(deadline - System.currentTimeMillis());
            }
        }
        assertThat(updates).isNotEmpty();
        ParticipantsUpdatePayload update = updates.get(0);
        assertThat(update.participants()).hasSize(1);
        assertThat(update.participants().get(0).userId()).isEqualTo(String.valueOf(ownerId));
        assertThat(update.participants().get(0).displayName()).isEqualTo("Alice");
        assertThat(update.participants().get(0).color()).isNotBlank().startsWith("#");
        assertThat(update.participants().get(0).role()).isEqualTo("OWNER");
    }

    // =========================================================================
    // Test 2 — DRAW is broadcast to the board topic
    // =========================================================================

    /**
     * When a member sends a DRAW action, it is broadcast to all subscribers of the
     * board topic with the correct type, boardId, and userId.
     */
    @Test
    void draw_action_is_broadcast_to_board_topic() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> drawFuture = new CompletableFuture<>();

        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, drawFuture));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW",
                        "data", Map.of("type", "stroke", "tool", "pencil",
                                "payload", Map.of("color", "#FF0000"))));

        BroadcastCanvasMessage msg = drawFuture.get(5, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.type()).isEqualTo("DRAW");
        assertThat(msg.boardId()).isEqualTo(board.getId().toString());
        assertThat(msg.userId()).isEqualTo(String.valueOf(ownerId));
    }

    // =========================================================================
    // Test 3 — DRAW event is persisted in the database
    // =========================================================================

    /**
     * When a member sends a DRAW action, it is persisted in {@code canvas_event}
     * with the correct board, tenant, and user identifiers.
     */
    @Test
    void draw_action_is_persisted_in_db() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> drawFuture = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, drawFuture));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW",
                        "data", Map.of("type", "shape", "tool", "rectangle",
                                "payload", Map.of("x", 10, "y", 20, "w", 100, "h", 50))));

        // Wait for broadcast to confirm message was processed
        drawFuture.get(5, TimeUnit.SECONDS);

        // Allow a brief moment for the transactional save to commit
        Thread.sleep(200);

        List<CanvasEvent> events = canvasEventRepository
                .findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(board.getId(), tenantId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(CanvasEventType.DRAW);
        assertThat(events.get(0).getUserId()).isEqualTo(ownerId);
        assertThat(events.get(0).getTenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).getPayload()).contains("rectangle");
    }

    // =========================================================================
    // Test 4 — Unknown message type is silently dropped
    // =========================================================================

    /**
     * When a member sends a message with an unknown type, the server logs WARN and
     * drops it silently; the session remains open and subsequent valid messages work.
     */
    @Test
    void unknown_message_type_is_silently_dropped_and_session_remains_open() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> drawFuture = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, drawFuture));

        // Send unknown type — should be silently dropped
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "UNKNOWN_XYZ", "data", Map.of()));

        // Subsequent valid DRAW must still work
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "DRAW",
                        "data", Map.of("type", "stroke", "tool", "pencil", "payload", Map.of())));

        BroadcastCanvasMessage msg = drawFuture.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("DRAW");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 5 — VIEWER cannot send UNDO
    // =========================================================================

    /**
     * When a VIEWER sends UNDO, the server rejects it and sends an error to
     * {@code /user/queue/errors}. The session remains open.
     */
    @Test
    void viewer_cannot_send_undo() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        // Add viewer member
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));

        StompSession session = connectAs(viewerToken);
        CompletableFuture<Map> errorFuture = new CompletableFuture<>();
        session.subscribe("/user/queue/errors", framHandler(Map.class, errorFuture));
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());

        // Wait for both subscriptions to be fully registered.  The /user/queue/errors
        // subscription routes through UserDestinationMessageHandler before reaching
        // SimpleBroker, adding latency vs. /topic/… subscriptions. Without this pause,
        // convertAndSendToUser() can be called before the translated subscription is
        // registered, causing the error message to be silently dropped.
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "UNDO", "data", Map.of("eventId", UUID.randomUUID().toString())));

        Map error = errorFuture.get(8, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("error").toString()).containsIgnoringCase("VIEWER");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 6 — CURSOR_MOVE is broadcast but not persisted
    // =========================================================================

    /**
     * CURSOR_MOVE is broadcast to the board topic but not stored in {@code canvas_event}.
     */
    @Test
    void cursor_move_is_broadcast_but_not_persisted() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CURSOR_MOVE", "data", Map.of("x", 100, "y", 200)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("CURSOR_MOVE");

        Thread.sleep(200);
        List<CanvasEvent> events = canvasEventRepository
                .findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(board.getId(), tenantId);
        assertThat(events).isEmpty();
    }

    // =========================================================================
    // Test 7 — LEAVE removes participant from PARTICIPANTS_UPDATE
    // =========================================================================

    /**
     * After a LEAVE action, the participant is removed from the presence list in the
     * subsequent PARTICIPANTS_UPDATE.
     *
     * <p>Note: we deliberately do NOT subscribe to the main board topic here for the same
     * reason as in {@link #join_assigns_color_and_emits_participants_update()} — EN08.1's
     * {@code WhiteboardPresenceRegistry} would fire a {@code PresencePayload} (field
     * {@code userIds}) to the presence topic, landing in {@code updates} as a
     * {@code ParticipantsUpdatePayload} with {@code participants = null} and breaking
     * the index-based assertions below.
     */
    @Test
    void leave_removes_participant_from_participants_update() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        List<ParticipantsUpdatePayload> updates = new ArrayList<>();

        session.subscribe("/topic/whiteboard/" + board.getId() + "/presence",
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(final StompHeaders headers) {
                        return ParticipantsUpdatePayload.class;
                    }

                    @Override
                    public void handleFrame(final StompHeaders headers, final Object payload) {
                        synchronized (updates) {
                            updates.add((ParticipantsUpdatePayload) payload);
                            updates.notifyAll();
                        }
                    }
                });

        // Brief pause so SimpleBroker registers the subscription before the first SEND.
        Thread.sleep(100);

        // JOIN
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "JOIN", "data", Map.of("displayName", "Bob")));
        synchronized (updates) {
            long deadline = System.currentTimeMillis() + 8000;
            while (updates.isEmpty() && System.currentTimeMillis() < deadline) {
                updates.wait(deadline - System.currentTimeMillis());
            }
        }
        assertThat(updates).isNotEmpty();
        assertThat(updates.get(0).participants()).hasSize(1);

        // LEAVE
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "LEAVE", "data", Map.of()));
        synchronized (updates) {
            long deadline = System.currentTimeMillis() + 8000;
            while (updates.size() < 2 && System.currentTimeMillis() < deadline) {
                updates.wait(deadline - System.currentTimeMillis());
            }
        }
        assertThat(updates).hasSizeGreaterThanOrEqualTo(2);
        assertThat(updates.get(1).participants()).isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Establishes a STOMP connection authenticated with the given bearer token on the STOMP
     * {@code CONNECT} frame's native {@code Authorization} header.
     *
     * @param rawToken the raw bearer token to send as {@code Authorization: Bearer <token>} on
     *                 the CONNECT frame
     * @return an open STOMP session
     */
    private StompSession connectAs(final String rawToken) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + rawToken);

        String url = "ws://localhost:" + port + "/api/collaboratif/ws/whiteboard";
        StompSession session = client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Creates a board owned by {@code ownerId} within {@code tenantId} and saves it
     * directly via the JPA repositories.
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
                future.complete(type.cast(payload));
            }
        };
    }

    /**
     * Returns a no-op {@link StompFrameHandler} for subscriptions where the payload
     * is not needed.
     *
     * @return a no-op frame handler
     */
    private StompFrameHandler noOpHandler() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                // deliberately empty
            }
        };
    }
}
