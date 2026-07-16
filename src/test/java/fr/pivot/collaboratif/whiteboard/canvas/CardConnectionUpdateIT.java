package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.7.2 — applying a partial style patch to an existing
 * {@link CardConnection} over STOMP ({@code connection:update}).
 *
 * <p>Verifies:
 * <ol>
 *   <li>A mono-field patch mutates only the targeted field, broadcasting the full connector,
 *       emitter included.</li>
 *   <li>A multi-field patch mutates exactly the fields present, leaving the rest untouched.</li>
 *   <li>An explicit {@code label:null} clears the label; an absent {@code label} preserves it —
 *       the two are distinguished.</li>
 *   <li>An empty patch (no style field beyond {@code id}) is a silent no-op: no write, no
 *       broadcast.</li>
 *   <li>An out-of-whitelist {@code shape}/{@code arrow} is rejected for that field alone, without
 *       aborting the rest of a mixed patch.</li>
 *   <li>An unknown/already-deleted connector id is a tolerant no-op.</li>
 *   <li>A connector id belonging to another board is refused (no cross-board restyle via a
 *       guessed id).</li>
 *   <li>A VIEWER cannot style a connector (silent refusal, no DB change).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class CardConnectionUpdateIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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
    private CardRepository cardRepository;

    @Autowired
    private CardConnectionRepository cardConnectionRepository;

    private final List<StompSession> openSessions = new ArrayList<>();

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
    // Test 1 — mono-field patch mutates only the targeted field (AC1, AC2)
    // =========================================================================

    @Test
    void connection_update_single_field_patch_leaves_other_fields_unchanged() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        CardConnection connection = seedConnection(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE",
                        "data", Map.of("id", connection.getId().toString(), "shape", "orthogonal")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("connection:updated");
        Map<String, Object> connData = map(msg);
        assertThat(connData.get("id")).isEqualTo(connection.getId().toString());
        assertThat(connData.get("shape")).isEqualTo("orthogonal");
        // Every other field untouched — still the spec-mandated creation defaults.
        assertThat(connData.get("arrow")).isEqualTo("none");
        assertThat(connData.get("dashed")).isEqualTo(false);
        assertThat(((Number) connData.get("width")).intValue()).isEqualTo(2);
        assertThat(connData.get("label")).isNull();
        assertThat(connData.get("color")).isNull();

        Thread.sleep(200);
        CardConnection reloaded = cardConnectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getShape()).isEqualTo("orthogonal");
        assertThat(reloaded.getArrow()).isEqualTo("none");
        assertThat(reloaded.isDashed()).isFalse();
        assertThat(reloaded.getWidth()).isEqualTo(2);
    }

    // =========================================================================
    // Test 2 — multi-field patch mutates only the fields present (AC1)
    // =========================================================================

    @Test
    void connection_update_multi_field_patch_updates_only_present_fields() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        CardConnection connection = seedConnection(board.getId(), tenantId);
        connection.setLabel("original label");
        connection = cardConnectionRepository.save(connection);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        Map<String, Object> patch = new HashMap<>();
        patch.put("id", connection.getId().toString());
        patch.put("color", "#FF0000");
        patch.put("width", 5);
        patch.put("dashed", true);
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE", "data", patch));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        Map<String, Object> connData = map(msg);
        assertThat(connData.get("color")).isEqualTo("#FF0000");
        assertThat(((Number) connData.get("width")).intValue()).isEqualTo(5);
        assertThat(connData.get("dashed")).isEqualTo(true);
        // label and shape were not in the patch — untouched.
        assertThat(connData.get("label")).isEqualTo("original label");
        assertThat(connData.get("shape")).isEqualTo("curved");
        assertThat(connData.get("arrow")).isEqualTo("none");
    }

    // =========================================================================
    // Test 3 — explicit label:null clears the label; absence preserves it (AC3)
    // =========================================================================

    @Test
    void connection_update_label_null_explicit_clears_label() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        CardConnection connection = seedConnection(board.getId(), tenantId);
        connection.setLabel("to be cleared");
        connection = cardConnectionRepository.save(connection);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        Map<String, Object> patch = new HashMap<>();
        patch.put("id", connection.getId().toString());
        patch.put("label", null);
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE", "data", patch));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        Map<String, Object> connData = map(msg);
        assertThat(connData.get("label")).isNull();

        Thread.sleep(200);
        CardConnection reloaded = cardConnectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getLabel()).isNull();
    }

    // =========================================================================
    // Test 4 — an empty patch (no style field) is a silent no-op (AC4)
    // =========================================================================

    @Test
    void connection_update_empty_patch_is_noop_no_broadcast() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        CardConnection connection = seedConnection(board.getId(), tenantId);

        StompSession session = connectAs(token);
        List<BroadcastCanvasMessage> received = new CopyOnWriteArrayList<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                collectingHandler(BroadcastCanvasMessage.class, received));
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE", "data", Map.of("id", connection.getId().toString())));

        // A subsequent, valid CARD_CREATE must still work and be observed — proves the no-op
        // patch never threw and the session survived, while giving a synchronisation point to
        // safely assert on `received` right after.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));
        Thread.sleep(300);

        assertThat(received).noneMatch(m -> "connection:updated".equals(m.type()));
        assertThat(received).anyMatch(m -> "card:created".equals(m.type()));
        assertThat(session.isConnected()).isTrue();

        CardConnection reloaded = cardConnectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getShape()).isEqualTo("curved");
        assertThat(reloaded.getArrow()).isEqualTo("none");
        assertThat(reloaded.isDashed()).isFalse();
        assertThat(reloaded.getWidth()).isEqualTo(2);
        assertThat(reloaded.getLabel()).isNull();
        assertThat(reloaded.getColor()).isNull();
    }

    // =========================================================================
    // Test 5 — an out-of-whitelist shape/arrow is rejected for that field alone (AC5)
    // =========================================================================

    @Test
    void connection_update_invalid_shape_is_rejected_but_other_fields_in_same_patch_apply() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        CardConnection connection = seedConnection(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        Map<String, Object> patch = new HashMap<>();
        patch.put("id", connection.getId().toString());
        patch.put("shape", "zigzag");
        patch.put("arrow", "bogus");
        patch.put("color", "#123456");
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE", "data", patch));

        // A patch with at least one valid field (color) still broadcasts the connector, with
        // shape/arrow left at their previous (default) values — field-level rejection, not
        // whole-patch rejection.
        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        Map<String, Object> connData = map(msg);
        assertThat(connData.get("color")).isEqualTo("#123456");
        assertThat(connData.get("shape")).isEqualTo("curved");
        assertThat(connData.get("arrow")).isEqualTo("none");
    }

    @Test
    void connection_update_patch_with_only_an_invalid_shape_is_noop() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        CardConnection connection = seedConnection(board.getId(), tenantId);

        StompSession session = connectAs(token);
        List<BroadcastCanvasMessage> received = new CopyOnWriteArrayList<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                collectingHandler(BroadcastCanvasMessage.class, received));
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE",
                        "data", Map.of("id", connection.getId().toString(), "shape", "zigzag")));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));
        Thread.sleep(300);

        assertThat(received).noneMatch(m -> "connection:updated".equals(m.type()));
        assertThat(received).anyMatch(m -> "card:created".equals(m.type()));
        CardConnection reloaded = cardConnectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getShape()).isEqualTo("curved");
    }

    // =========================================================================
    // Test 6 — an unknown/already-deleted connector id is a tolerant no-op (AC6)
    // =========================================================================

    @Test
    void connection_update_nonexistent_id_is_tolerant_noop() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        UUID neverExistedId = UUID.randomUUID();

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE",
                        "data", Map.of("id", neverExistedId.toString(), "shape", "orthogonal")));

        // A subsequent, valid CARD_CREATE must still work — proves the no-op update never threw.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 7 — a connector id belonging to another board is refused (AC7, AC8)
    // =========================================================================

    @Test
    void connection_update_cross_board_id_is_refused() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        CardConnection connectionOnB = seedConnection(boardB.getId(), tenantId);

        StompSession session = connectAs(token);
        List<BroadcastCanvasMessage> received = new CopyOnWriteArrayList<>();
        session.subscribe("/topic/whiteboard/" + boardA.getId(),
                collectingHandler(BroadcastCanvasMessage.class, received));
        Thread.sleep(100);

        session.send("/app/whiteboard/" + boardA.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE",
                        "data", Map.of("id", connectionOnB.getId().toString(), "shape", "orthogonal")));

        session.send("/app/whiteboard/" + boardA.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));
        Thread.sleep(300);

        assertThat(received).noneMatch(m -> "connection:updated".equals(m.type()));
        assertThat(received).anyMatch(m -> "card:created".equals(m.type()));
        CardConnection reloaded = cardConnectionRepository.findById(connectionOnB.getId()).orElseThrow();
        assertThat(reloaded.getShape()).isEqualTo("curved");
    }

    // =========================================================================
    // Test 8 — a VIEWER cannot style a connector (AC9)
    // =========================================================================

    @Test
    void viewer_cannot_update_connection() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        CardConnection connection = seedConnection(board.getId(), tenantId);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_UPDATE",
                        "data", Map.of("id", connection.getId().toString(), "shape", "orthogonal")));

        Thread.sleep(300);
        CardConnection reloaded = cardConnectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getShape()).isEqualTo("curved");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private Board createBoardWithOwner(final long tenantId, final long ownerId) {
        Board board = new Board("Test board", tenantId, ownerId, Instant.now());
        boardRepository.save(board);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, Instant.now()));
        return board;
    }

    private Card seedCard(final UUID boardId, final long tenantId) {
        Card card = new Card(boardId, tenantId, CardType.TEXT, "seed", 0, 0, Instant.now());
        return cardRepository.save(card);
    }

    private CardConnection seedConnection(final UUID boardId, final long tenantId) {
        Card cardA = seedCard(boardId, tenantId);
        Card cardB = seedCard(boardId, tenantId);
        CardConnection connection =
                new CardConnection(boardId, tenantId, cardA.getId(), cardB.getId(), Instant.now());
        return cardConnectionRepository.save(connection);
    }

    private long seedTenant() throws Exception {
        return PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
    }

    private long seedUser(final long tenantId) throws Exception {
        return PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
    }

    private String issueToken(final long userId) throws Exception {
        return PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userId, "active", Instant.now().plusSeconds(3600));
    }

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

    private <T> StompFrameHandler collectingHandler(final Class<T> type, final List<T> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                sink.add(type.cast(payload));
            }
        };
    }

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
