package fr.pivot.collaboratif.whiteboard.canvas.table;

import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.6.6 — the {@code TABLE} card type and its spreadsheet-grid
 * {@code content} over the generic {@code card:*} STOMP contract shipped by EN08.4.
 *
 * <p>This US is additive on top of EN08.4's already-generic {@link Card}/{@code CARD_*}
 * pipeline (no new STOMP action, no new persistence path — a {@code TABLE} card is a
 * {@link Card} like any other, its grid living in {@code content} as JSON). These tests
 * therefore mostly confirm that the existing generic guards (lock, VIEWER refusal,
 * cross-board scoping) hold for {@code type=TABLE} exactly as they do for {@code TEXT}
 * (covered by {@code WhiteboardCardIT}), plus the one genuinely new behaviour this US
 * ships: server-side sanitisation of TABLE cell content ({@link TableCardContentSanitizer}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardTableCardIT {

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
    // AC1 — CARD_CREATE with type=TABLE persists a TABLE card and broadcasts card:created
    // =========================================================================

    @Test
    void card_create_with_type_table_persists_grid_content_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        String grid = "{\"rows\":[[\"a\",\"b\"],[\"c\",\"d\"]]}";
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create",
                        "data", Map.of("type", "TABLE", "content", grid, "width", 240.0, "height", 76.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("TABLE");
        assertThat(cardData.get("content")).isEqualTo(grid);

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.TABLE);
        assertThat(cards.get(0).getContent()).isEqualTo(grid);
    }

    // =========================================================================
    // AC3 — CARD_UPDATE fills an existing TABLE card's grid and broadcasts card:updated
    // =========================================================================

    @Test
    void card_update_fills_existing_table_grid_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedTableCard(board.getId(), tenantId, "{\"rows\":[[\"\",\"\"]]}", false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        String pasted = "{\"rows\":[[\"x\",\"y\"],[\"z\",\"w\"]]}";
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:update", "data", Map.of("id", card.getId().toString(), "content", pasted)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:updated");
        assertThat(msg.data().get("content")).isEqualTo(pasted);

        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo(pasted);
    }

    // =========================================================================
    // AC6 — move/resize/update on a locked TABLE card: 0 mutation, no broadcast
    // =========================================================================

    @Test
    void locked_table_card_move_resize_update_are_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        String originalGrid = "{\"rows\":[[\"a\"]]}";
        Card card = seedTableCard(board.getId(), tenantId, originalGrid, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:move",
                        "data", Map.of("id", card.getId().toString(), "posX", 50.0, "posY", 50.0)));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:resize",
                        "data", Map.of("id", card.getId().toString(), "width", 500.0, "height", 500.0)));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:update",
                        "data", Map.of("id", card.getId().toString(), "content", "{\"rows\":[[\"z\"]]}")));

        Thread.sleep(400);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
        assertThat(reloaded.getWidth()).isEqualTo(192.0);
        assertThat(reloaded.getHeight()).isEqualTo(128.0);
        assertThat(reloaded.getContent()).isEqualTo(originalGrid);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // AC8 (Security) — VIEWER cannot mutate a TABLE card
    // =========================================================================

    @Test
    void viewer_cannot_mutate_table_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        String originalGrid = "{\"rows\":[[\"a\"]]}";
        Card card = seedTableCard(board.getId(), tenantId, originalGrid, false);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:update",
                        "data", Map.of("id", card.getId().toString(), "content", "{\"rows\":[[\"hacked\"]]}")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo(originalGrid);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Security (content sanitisation) — a <script>/markup cell is stripped on CARD_CREATE
    // =========================================================================

    @Test
    void table_cell_markup_is_sanitized_on_create() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        String malicious = "{\"rows\":[[\"<script>alert(1)</script>\",\"<img src=x onerror=alert(2)>\"]]}";
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("type", "TABLE", "content", malicious)));

        future.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        String persisted = cards.get(0).getContent();
        assertThat(persisted).doesNotContain("<script", "</script>", "<img", "onerror", "<", ">");
        assertThat(persisted).contains("alert(1)", "alert(2)");
    }

    // =========================================================================
    // Security (content sanitisation) — a <script>/markup cell is stripped on CARD_UPDATE
    // =========================================================================

    @Test
    void table_cell_markup_is_sanitized_on_update() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedTableCard(board.getId(), tenantId, "{\"rows\":[[\"\"]]}", false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        String malicious = "{\"rows\":[[\"<b>bold</b> pasted from a web page\"]]}";
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:update", "data", Map.of("id", card.getId().toString(), "content", malicious)));

        future.get(5, TimeUnit.SECONDS);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getContent()).doesNotContain("<b>", "</b>");
        assertThat(reloaded.getContent()).contains("bold pasted from a web page");
    }

    // =========================================================================
    // AC7 (Error) — a card:* mutation on a TABLE card from another board: 0 rows, no leak
    // =========================================================================

    @Test
    void table_card_from_another_board_is_not_mutable_via_forged_destination() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        String originalGrid = "{\"rows\":[[\"a\"]]}";
        Card cardOnA = seedTableCard(boardA.getId(), tenantId, originalGrid, false);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + boardB.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + boardB.getId() + "/action",
                Map.of("type", "card:update",
                        "data", Map.of("id", cardOnA.getId().toString(), "content", "{\"rows\":[[\"leaked\"]]}")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(cardOnA.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo(originalGrid);
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

    private Card seedTableCard(final UUID boardId, final long tenantId, final String grid, final boolean locked) {
        Card card = new Card(boardId, tenantId, CardType.TABLE, grid, 0, 0, Instant.now());
        card.setLocked(locked);
        return cardRepository.save(card);
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
