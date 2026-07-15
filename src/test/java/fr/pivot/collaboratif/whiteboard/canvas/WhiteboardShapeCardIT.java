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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.6.3 — {@link CardType#SHAPE} cards over the {@code card:*} STOMP
 * contract mutualised by EN08.4/US08.6.1.
 *
 * <p>Verifies:
 * <ol>
 *   <li>CARD_CREATE with {@code type=SHAPE} persists with the generic Card defaults (parity
 *       spec §1.5) and a sanitised default style content.</li>
 *   <li>Explicit dimensions/colour/layer and a well-formed style content are respected as-is.</li>
 *   <li>A malicious/out-of-whitelist style content is sanitised server-side before persistence
 *       (correctif §6.4) rather than persisted raw.</li>
 *   <li>A misspelled type falls back to {@link CardType#TEXT} — the shape sanitiser is never
 *       applied to a card that isn't exactly {@code SHAPE}.</li>
 *   <li>CARD_MOVE/CARD_RESIZE/CARD_RECOLOR/CARD_UPDATE on a locked SHAPE are refused silently.</li>
 *   <li>CARD_UPDATE with a new style content sanitises it and broadcasts to the whole room.</li>
 *   <li>A VIEWER cannot mutate a SHAPE card (silent refusal, no DB change).</li>
 * </ol>
 *
 * <p>{@code card:delete} is deliberately <strong>not</strong> re-tested here for a lock guard:
 * EN08.4's {@code CardRepository#deleteByIdAndBoardId} is intentionally not guarded by
 * {@code locked} (see that method's Javadoc, EN08.4 Gate 1 decision) and this US's own Notes
 * d'implémentation direct reuse of the {@code card:*} contracts "as-is" — {@link
 * WhiteboardCardIT#card_delete_already_deleted_is_idempotent_noop} already covers the generic,
 * type-agnostic delete contract SHAPE reuses unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardShapeCardIT {

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
    // AC1 — CARD_CREATE with type=SHAPE persists with generic Card defaults
    // =========================================================================

    @Test
    void shape_create_persists_with_card_defaults_and_default_style() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("type", "SHAPE")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("SHAPE");
        assertThat(cardData.get("content")).isEqualTo("rect|#A5B4FC|none|1|0");
        assertThat(((Number) cardData.get("width")).doubleValue()).isEqualTo(192.0);
        assertThat(((Number) cardData.get("height")).doubleValue()).isEqualTo(128.0);
        assertThat(cardData.get("color")).isEqualTo("#FFEB3B");
        assertThat(((Number) cardData.get("layer")).intValue()).isEqualTo(1);
        assertThat(cardData.get("locked")).isEqualTo(false);

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.SHAPE);
        assertThat(cards.get(0).getContent()).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    // =========================================================================
    // AC2 — explicit dimensions/color/layer and well-formed style content respected
    // =========================================================================

    @Test
    void shape_create_with_explicit_values_are_respected() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of(
                        "type", "SHAPE",
                        "content", "circle|#445566|#112233|0.8|0",
                        "width", 300.0,
                        "height", 150.0,
                        "color", "#00FF00",
                        "layer", 3)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("SHAPE");
        assertThat((String) cardData.get("content")).isEqualTo("circle|#445566|#112233|0.8|0");
        assertThat(((Number) cardData.get("width")).doubleValue()).isEqualTo(300.0);
        assertThat(((Number) cardData.get("height")).doubleValue()).isEqualTo(150.0);
        assertThat(cardData.get("color")).isEqualTo("#00FF00");
        assertThat(((Number) cardData.get("layer")).intValue()).isEqualTo(3);
    }

    // =========================================================================
    // Security — malicious style content is sanitised before persistence (correctif §6.4)
    // =========================================================================

    @Test
    void shape_create_with_malicious_style_content_is_sanitized() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of(
                        "type", "SHAPE",
                        "content", "<script>alert(1)</script>|url(javascript:alert(1))|javascript:alert(1)|1|0")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        String content = (String) cardData.get("content");
        assertThat(content).doesNotContain("script").doesNotContain("javascript:");
        assertThat(content).isEqualTo("rect|#A5B4FC|none|1|0");

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards.get(0).getContent()).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    // =========================================================================
    // Error case — a misspelled type falls back to TEXT, never becomes a SHAPE
    // =========================================================================

    @Test
    void misspelled_shape_type_falls_back_to_text_and_is_not_style_sanitized() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of(
                        "type", "SHAPPE", "content", "plain text, not JSON style")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("TEXT");
        // Content is untouched by the shape sanitiser — it stays exactly what was sent.
        assertThat(cardData.get("content")).isEqualTo("plain text, not JSON style");
    }

    // =========================================================================
    // Locked guard — CARD_MOVE/RESIZE/RECOLOR/UPDATE on a locked SHAPE are refused silently
    // =========================================================================

    @Test
    void shape_move_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card shape = seedShapeCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", shape.getId().toString(), "posX", 999.0, "posY", 999.0)));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(shape.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void shape_resize_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card shape = seedShapeCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RESIZE",
                        "data", Map.of("id", shape.getId().toString(), "width", 500.0, "height", 500.0)));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(shape.getId()).orElseThrow();
        assertThat(reloaded.getWidth()).isEqualTo(192.0);
        assertThat(reloaded.getHeight()).isEqualTo(128.0);
    }

    @Test
    void shape_recolor_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card shape = seedShapeCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RECOLOR",
                        "data", Map.of("id", shape.getId().toString(), "color", "#000000")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(shape.getId()).orElseThrow();
        assertThat(reloaded.getColor()).isEqualTo("#FFEB3B");
    }

    @Test
    void shape_update_content_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card shape = seedShapeCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_UPDATE",
                        "data", Map.of("id", shape.getId().toString(),
                                "content", "circle|#112233|none|1|0")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(shape.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo("rect|#A5B4FC|none|1|0");
    }

    // =========================================================================
    // CARD_UPDATE — new style content is sanitized and broadcast to the whole room
    // =========================================================================

    @Test
    void shape_update_content_sanitizes_new_style_and_broadcasts_to_whole_room() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card shape = seedShapeCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_UPDATE",
                        "data", Map.of("id", shape.getId().toString(),
                                "content", "diamond|#112233|not-a-hex|1|0")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:updated");
        assertThat(msg.data().get("id")).isEqualTo(shape.getId().toString());
        String broadcastContent = (String) msg.data().get("content");
        assertThat(broadcastContent).isEqualTo("diamond|#112233|none|1|0");

        Card reloaded = cardRepository.findById(shape.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo(broadcastContent);
    }

    // =========================================================================
    // Security — a VIEWER cannot mutate a SHAPE card
    // =========================================================================

    @Test
    void viewer_cannot_mutate_shape_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Card shape = seedShapeCard(board.getId(), tenantId, false);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RECOLOR",
                        "data", Map.of("id", shape.getId().toString(), "color", "#000000")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(shape.getId()).orElseThrow();
        assertThat(reloaded.getColor()).isEqualTo("#FFEB3B");
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

    private Card seedShapeCard(final UUID boardId, final long tenantId, final boolean locked) {
        Card card = new Card(boardId, tenantId, CardType.SHAPE, "rect|#A5B4FC|none|1|0", 0, 0, Instant.now());
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
