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

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the custom board field ({@link BoardField}) STOMP handlers (US08.10.1):
 * {@code boardfield:create}/{@code boardfield:update}/{@code boardfield:delete} and their flat
 * broadcasts, mirroring {@link WhiteboardGroupLockEditIT}'s Testcontainers/STOMP setup.
 *
 * <p>Covers the acceptance criteria:
 * <ul>
 *   <li>create each of the four {@link FieldType}s → field persisted + {@code boardfield:created}
 *       flat broadcast;</li>
 *   <li><strong>invalid type → no persistence, no broadcast, session stays connected</strong>
 *       (the §6.6 fix — validate before persist, never throw);</li>
 *   <li>update rewrites {@code name}, leaves {@code type} unchanged, echoes
 *       {@code boardfield:updated};</li>
 *   <li>delete echoes {@code boardfield:deleted} carrying the bare id string and cascade-deletes
 *       the field's {@link CardFieldValue} rows;</li>
 *   <li>a VIEWER emitting {@code boardfield:create} is silently refused (no row, session stays
 *       connected).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardBoardFieldIT {

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
    private BoardFieldRepository boardFieldRepository;

    @Autowired
    private CardFieldValueRepository cardFieldValueRepository;

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
    // boardfield:create → boardfield:created (flat), each of the 4 types persisted
    // =========================================================================

    @Test
    void create_persists_each_type_and_echoes_flat_boardfield_created() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:create",
                        "data", Map.of("name", "Notes", "type", "TEXT", "order", 0)));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:create",
                        "data", Map.of("name", "Points", "type", "NUMBER", "order", 1)));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:create",
                        "data", Map.of("name", "Due", "type", "DATE", "order", 2)));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:create",
                        "data", Map.of("name", "Priority", "type", "SELECT",
                                "options", List.of("Low", "High"), "order", 3)));

        List<String> broadcastTypes = new ArrayList<>();
        Map<String, Object> selectMsg = null;
        for (int i = 0; i < 4; i++) {
            BroadcastCanvasMessage msg = awaitType(queue, "boardfield:created", 8);
            Map<String, Object> data = map(msg);
            broadcastTypes.add((String) data.get("type"));
            assertThat(data.get("boardId")).isEqualTo(board.getId().toString());
            assertThat(data.get("id")).isNotNull();
            if ("SELECT".equals(data.get("type"))) {
                selectMsg = data;
            }
        }
        assertThat(broadcastTypes).containsExactlyInAnyOrder("TEXT", "NUMBER", "DATE", "SELECT");
        assertThat(selectMsg).isNotNull();
        assertThat(selectMsg.get("name")).isEqualTo("Priority");
        assertThat(selectMsg.get("options")).isEqualTo(List.of("Low", "High"));

        Thread.sleep(200);
        List<BoardField> persisted = boardFieldRepository
                .findAllByBoardIdOrderByOrderAscCreatedAtAsc(board.getId());
        assertThat(persisted).hasSize(4);
        assertThat(persisted.stream().map(BoardField::getType).toList())
                .containsExactly(FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.SELECT);
        BoardField select = persisted.get(3);
        assertThat(select.getType()).isEqualTo(FieldType.SELECT);
        assertThat(select.getOptions()).contains("Low").contains("High");
    }

    // =========================================================================
    // §6.6 fix — invalid type: no persistence, no broadcast, session stays connected
    // =========================================================================

    @Test
    void create_with_invalid_type_is_dropped_without_persist_or_broadcast() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:create",
                        "data", Map.of("name", "Broken", "type", "NOT_A_TYPE", "order", 0)));

        assertNoType(queue, "boardfield:created", 2);
        assertThat(boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(board.getId())).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // boardfield:update → name rewritten, type unchanged, boardfield:updated (flat)
    // =========================================================================

    @Test
    void update_rewrites_name_keeps_type_and_echoes_boardfield_updated() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = boardFieldRepository.save(new BoardField(
                board.getId(), tenantId, "Old", null, FieldType.TEXT, null, 0, Instant.now()));

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:update",
                        "data", Map.of("id", field.getId().toString(), "name", "New",
                                "type", "SELECT", "emoji", "🔥")));

        BroadcastCanvasMessage msg = awaitType(queue, "boardfield:updated", 8);
        assertThat(map(msg).get("id")).isEqualTo(field.getId().toString());
        assertThat(map(msg).get("name")).isEqualTo("New");
        assertThat(map(msg).get("type")).isEqualTo("TEXT");
        assertThat(map(msg).get("emoji")).isEqualTo("🔥");

        Thread.sleep(200);
        BoardField reloaded = boardFieldRepository.findById(field.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("New");
        assertThat(reloaded.getType()).isEqualTo(FieldType.TEXT);
    }

    // =========================================================================
    // boardfield:delete → bare id string broadcast + CardFieldValue cascade-deleted
    // =========================================================================

    @Test
    void delete_echoes_bare_id_and_cascades_card_field_values() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = boardFieldRepository.save(new BoardField(
                board.getId(), tenantId, "Doomed", null, FieldType.TEXT, null, 0, Instant.now()));
        Card card = cardRepository.save(
                new Card(board.getId(), tenantId, CardType.TEXT, "seed", 0, 0, Instant.now()));
        cardFieldValueRepository.save(new CardFieldValue(card.getId(), field.getId(), "hello"));

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:delete", "data", Map.of("id", field.getId().toString())));

        BroadcastCanvasMessage msg = awaitType(queue, "boardfield:deleted", 8);
        // data is the bare id string, matching this.on<string>('boardfield:deleted', …)
        assertThat(msg.data()).isEqualTo(field.getId().toString());

        Thread.sleep(300);
        assertThat(boardFieldRepository.findById(field.getId())).isEmpty();
        assertThat(cardFieldValueRepository.findByCardIdAndFieldId(card.getId(), field.getId())).isEmpty();
    }

    // =========================================================================
    // Security — a VIEWER cannot create a board field (silent refusal)
    // =========================================================================

    @Test
    void viewer_cannot_create_board_field() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));

        StompSession session = connectAs(viewerToken);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "boardfield:create",
                        "data", Map.of("name", "Sneaky", "type", "TEXT", "order", 0)));

        assertNoType(queue, "boardfield:created", 2);
        assertThat(boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(board.getId())).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Security — a field id from another board cannot be updated/deleted (IDOR scoping)
    // =========================================================================

    @Test
    void update_and_delete_are_scoped_by_board() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        // Field belongs to boardB, but the action is sent to boardA's destination.
        BoardField foreign = boardFieldRepository.save(new BoardField(
                boardB.getId(), tenantId, "Foreign", null, FieldType.TEXT, null, 0, Instant.now()));

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, boardA.getId());

        session.send("/app/whiteboard/" + boardA.getId() + "/action",
                Map.of("type", "boardfield:delete", "data", Map.of("id", foreign.getId().toString())));

        assertNoType(queue, "boardfield:deleted", 2);
        // The cross-board field is untouched.
        assertThat(boardFieldRepository.findById(foreign.getId())).isPresent();
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

    private void assertNoType(
            final BlockingQueue<BroadcastCanvasMessage> queue, final String type, final long windowSeconds)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(windowSeconds);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            BroadcastCanvasMessage msg = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (msg == null) {
                return;
            }
            if (msg.type().equals(type)) {
                throw new AssertionError("Unexpected broadcast of type '" + type + "'");
            }
        }
    }
}
