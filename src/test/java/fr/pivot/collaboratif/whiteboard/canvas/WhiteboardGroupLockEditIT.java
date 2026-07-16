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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.list;
import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the wire-contract fixes P4/P5 (fix/whiteboard-wire-contract): the
 * grouping/locking/concurrent-editing STOMP handlers that reuse existing {@link Card} columns
 * (no new table), and the {@code board:presence} broadcast on the main topic.
 *
 * <p>Each test asserts the <strong>exact</strong> outgoing wire type and payload shape the
 * frontend ({@code board.store.ts}) subscribes to:
 * <ul>
 *   <li>{@code card:lock} → {@code cards:locked} {@code {ids, locked}};</li>
 *   <li>{@code cards:group} → {@code cards:grouped} {@code {cardIds, groupId}} (server-assigned id);</li>
 *   <li>{@code cards:ungroup} → {@code cards:ungrouped} carrying the bare {@code groupId} string;</li>
 *   <li>{@code cards:group-color} → {@code cards:group-colored} {@code {groupId, color}};</li>
 *   <li>{@code card:editing} → {@code card:editing} {@code {cardId, userId, name, editing}} (ephemeral);</li>
 *   <li>{@code board:join} → {@code board:presence} {@code [{id, name, avatar}]} on the main topic.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardGroupLockEditIT {

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
    // card:lock → cards:locked {ids, locked}
    // =========================================================================

    @Test
    void card_lock_applies_lock_and_echoes_cards_locked() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:lock", "data", Map.of(
                        "ids", List.of(cardA.getId().toString(), cardB.getId().toString()),
                        "locked", true)));

        BroadcastCanvasMessage msg = awaitType(queue, "cards:locked", 6);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) map(msg).get("ids");
        assertThat(ids).containsExactlyInAnyOrder(cardA.getId().toString(), cardB.getId().toString());
        assertThat(map(msg).get("locked")).isEqualTo(true);

        Thread.sleep(200);
        assertThat(cardRepository.findById(cardA.getId()).orElseThrow().isLocked()).isTrue();
        assertThat(cardRepository.findById(cardB.getId()).orElseThrow().isLocked()).isTrue();
    }

    // =========================================================================
    // cards:group → cards:grouped {cardIds, groupId}
    // =========================================================================

    @Test
    void cards_group_assigns_server_group_id_and_echoes_cards_grouped() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cards:group", "data", Map.of(
                        "cardIds", List.of(cardA.getId().toString(), cardB.getId().toString()))));

        BroadcastCanvasMessage msg = awaitType(queue, "cards:grouped", 6);
        @SuppressWarnings("unchecked")
        List<String> cardIds = (List<String>) map(msg).get("cardIds");
        assertThat(cardIds).containsExactlyInAnyOrder(cardA.getId().toString(), cardB.getId().toString());
        String groupId = (String) map(msg).get("groupId");
        assertThat(groupId).isNotBlank();

        Thread.sleep(200);
        UUID expectedGroup = UUID.fromString(groupId);
        assertThat(cardRepository.findById(cardA.getId()).orElseThrow().getGroupId()).isEqualTo(expectedGroup);
        assertThat(cardRepository.findById(cardB.getId()).orElseThrow().getGroupId()).isEqualTo(expectedGroup);
    }

    // =========================================================================
    // cards:ungroup → cards:ungrouped (bare groupId string)
    // =========================================================================

    @Test
    void cards_ungroup_clears_group_and_echoes_bare_group_id_string() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        UUID groupId = UUID.randomUUID();
        Card cardA = seedGroupedCard(board.getId(), tenantId, groupId);
        Card cardB = seedGroupedCard(board.getId(), tenantId, groupId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cards:ungroup", "data", Map.of("groupId", groupId.toString())));

        BroadcastCanvasMessage msg = awaitType(queue, "cards:ungrouped", 6);
        // data is the bare groupId string, matching this.on<string>('cards:ungrouped', …)
        assertThat(msg.data()).isEqualTo(groupId.toString());

        Thread.sleep(200);
        assertThat(cardRepository.findById(cardA.getId()).orElseThrow().getGroupId()).isNull();
        assertThat(cardRepository.findById(cardB.getId()).orElseThrow().getGroupId()).isNull();
    }

    // =========================================================================
    // cards:group-color → cards:group-colored {groupId, color}
    // =========================================================================

    @Test
    void cards_group_color_recolors_group_and_echoes_cards_group_colored() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        UUID groupId = UUID.randomUUID();
        Card cardA = seedGroupedCard(board.getId(), tenantId, groupId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cards:group-color", "data", Map.of(
                        "groupId", groupId.toString(), "color", "#FF5722")));

        BroadcastCanvasMessage msg = awaitType(queue, "cards:group-colored", 6);
        assertThat(map(msg).get("groupId")).isEqualTo(groupId.toString());
        assertThat(map(msg).get("color")).isEqualTo("#FF5722");

        Thread.sleep(200);
        assertThat(cardRepository.findById(cardA.getId()).orElseThrow().getGroupColor()).isEqualTo("#FF5722");
    }

    // =========================================================================
    // card:editing → card:editing {cardId, userId, name, editing} (ephemeral, no persistence)
    // =========================================================================

    @Test
    void card_editing_broadcasts_enriched_editing_signal() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        // Wait for JOIN to be fully processed (presence stored) before the second frame — the
        // STOMP inbound channel is multi-threaded, so two back-to-back sends are not ordered; the
        // board:presence broadcast is emitted only after handleJoin has stored the participant meta.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:join", "data", Map.of("displayName", "Bob")));
        awaitType(queue, "board:presence", 8);
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:editing", "data", Map.of(
                        "cardId", card.getId().toString(), "editing", true)));

        BroadcastCanvasMessage msg = awaitType(queue, "card:editing", 8);
        assertThat(map(msg).get("cardId")).isEqualTo(card.getId().toString());
        assertThat(map(msg).get("userId")).isEqualTo(String.valueOf(ownerId));
        assertThat(map(msg).get("name")).isEqualTo("Bob");
        assertThat(map(msg).get("editing")).isEqualTo(true);
    }

    // =========================================================================
    // board:join → board:presence [{id, name, avatar}] on the main topic (P4)
    // =========================================================================

    @Test
    void join_broadcasts_board_presence_on_main_topic_with_display_name() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:join", "data", Map.of("displayName", "Carol", "avatarUrl", "http://x/a.png")));

        BroadcastCanvasMessage msg = awaitType(queue, "board:presence", 8);
        List<Object> users = list(msg);
        assertThat(users).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) users.get(0);
        assertThat(user.get("id")).isEqualTo(String.valueOf(ownerId));
        assertThat(user.get("name")).isEqualTo("Carol");
        assertThat(user.get("avatar")).isEqualTo("http://x/a.png");
    }

    // =========================================================================
    // Security — a VIEWER cannot lock or group cards (silent refusal)
    // =========================================================================

    @Test
    void viewer_cannot_lock_cards() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:lock", "data", Map.of(
                        "ids", List.of(card.getId().toString()), "locked", true)));

        Thread.sleep(300);
        assertThat(cardRepository.findById(card.getId()).orElseThrow().isLocked()).isFalse();
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

    private Card seedGroupedCard(final UUID boardId, final long tenantId, final UUID groupId) {
        Card card = new Card(boardId, tenantId, CardType.TEXT, "seed", 0, 0, Instant.now());
        card.setGroupId(groupId);
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

    private BlockingQueue<BroadcastCanvasMessage> subscribeQueue(final StompSession session, final UUID boardId) {
        BlockingQueue<BroadcastCanvasMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/whiteboard/" + boardId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return BroadcastCanvasMessage.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                queue.add((BroadcastCanvasMessage) payload);
            }
        });
        return queue;
    }

    private BroadcastCanvasMessage awaitType(
            final BlockingQueue<BroadcastCanvasMessage> queue, final String type, final long timeoutSeconds)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new AssertionError("Timed out waiting for broadcast type '" + type + "'");
            }
            BroadcastCanvasMessage msg = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (msg == null) {
                throw new AssertionError("Timed out waiting for broadcast type '" + type + "'");
            }
            if (msg.type().equals(type)) {
                return msg;
            }
        }
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
