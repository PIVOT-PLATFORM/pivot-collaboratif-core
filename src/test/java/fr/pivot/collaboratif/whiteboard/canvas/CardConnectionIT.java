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
 * Integration tests for US08.7.1 — creating and deleting a {@link CardConnection} over STOMP.
 *
 * <p>Verifies:
 * <ol>
 *   <li>CONNECTION_CREATE persists a connector with the exact spec-mandated defaults and
 *       broadcasts it to the whole room, emitter included.</li>
 *   <li>A self-link ({@code fromId === toId}) creates nothing and broadcasts nothing.</li>
 *   <li>A bidirectional duplicate ({@code (A,B)} then {@code (B,A)}) creates nothing the second
 *       time, in either direction.</li>
 *   <li>A {@code fromId}/{@code toId} that does not reference an existing card of this board is
 *       refused without an exception (correctif §6.5).</li>
 *   <li>CONNECTION_DELETE on an already-deleted/never-existing id is an idempotent no-op.</li>
 *   <li>A VIEWER cannot create or delete a connector (silent refusal, no DB change).</li>
 *   <li>A non-member of the board cannot mutate a connector (denied at the channel-interceptor
 *       level before reaching the handler, EN08.1).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class CardConnectionIT {

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
    // Test 1 — CONNECTION_CREATE persists with defaults and broadcasts to whole room
    // =========================================================================

    @Test
    void connection_create_persists_with_defaults_and_broadcasts_to_whole_room() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);

        // The emitter itself subscribes to the room topic — the AC requires the broadcast to
        // reach "toute la room (émetteur inclus)", so receiving it here on the emitter's own
        // subscription is the assertion, not an incidental test convenience.
        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardA.getId().toString(), "toId", cardB.getId().toString())));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("connection:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> connData = (Map<String, Object>) msg.data().get("connection");
        assertThat(connData.get("fromId")).isEqualTo(cardA.getId().toString());
        assertThat(connData.get("toId")).isEqualTo(cardB.getId().toString());
        assertThat(connData.get("shape")).isEqualTo("curved");
        assertThat(connData.get("arrow")).isEqualTo("none");
        assertThat(connData.get("dashed")).isEqualTo(false);
        assertThat(((Number) connData.get("width")).intValue()).isEqualTo(2);
        assertThat(connData.get("label")).isNull();
        assertThat(connData.get("color")).isNull();

        Thread.sleep(200);
        List<CardConnection> connections = cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId);
        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).getFromId()).isEqualTo(cardA.getId());
        assertThat(connections.get(0).getToId()).isEqualTo(cardB.getId());
    }

    // =========================================================================
    // Test 2 — self-link creates nothing and broadcasts nothing
    // =========================================================================

    @Test
    void connection_create_self_link_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", card.getId().toString(), "toId", card.getId().toString())));

        Thread.sleep(300);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 3 — bidirectional duplicate creates nothing, in either direction
    // =========================================================================

    @Test
    void connection_create_bidirectional_duplicate_is_refused_in_either_direction() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> firstCreated = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, firstCreated));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardA.getId().toString(), "toId", cardB.getId().toString())));
        firstCreated.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).hasSize(1);

        // Same direction again
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardA.getId().toString(), "toId", cardB.getId().toString())));
        Thread.sleep(300);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).hasSize(1);

        // Reverse direction
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardB.getId().toString(), "toId", cardA.getId().toString())));
        Thread.sleep(300);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).hasSize(1);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 4 — a missing/foreign card ref is refused without an exception (correctif §6.5)
    // =========================================================================

    @Test
    void connection_create_with_nonexistent_card_ref_is_refused_without_exception() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        UUID nonExistentCardId = UUID.randomUUID();

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardA.getId().toString(), "toId", nonExistentCardId.toString())));

        // A subsequent, valid CARD_CREATE must still work — proves the session/handler survived
        // the missing-ref case without any exception propagating up to the STOMP layer.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        assertThat(session.isConnected()).isTrue();
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).isEmpty();
    }

    // =========================================================================
    // Test 4b — a card belonging to another board is refused without an exception
    // =========================================================================

    @Test
    void connection_create_with_card_from_another_board_is_refused() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        Card cardOnA = seedCard(boardA.getId(), tenantId);
        Card cardOnB = seedCard(boardB.getId(), tenantId);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + boardA.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + boardA.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardOnA.getId().toString(), "toId", cardOnB.getId().toString())));

        Thread.sleep(300);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(boardA.getId(), tenantId)).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 5 — CONNECTION_DELETE on an already-deleted/never-existing id is idempotent
    // =========================================================================

    @Test
    void connection_delete_already_deleted_is_idempotent_noop() throws Exception {
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
                Map.of("type", "CONNECTION_DELETE", "data", Map.of("id", neverExistedId.toString())));

        // A subsequent, valid CARD_CREATE must still work — proves the no-op delete never threw.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 5b — deleting an id already cascaded away by an endpoint card's own deletion
    // =========================================================================

    @Test
    void connection_delete_tolerates_id_already_cascaded_by_card_deletion() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);
        CardConnection connection = new CardConnection(board.getId(), tenantId, cardA.getId(), cardB.getId(), Instant.now());
        connection = cardConnectionRepository.save(connection);
        UUID connectionId = connection.getId();

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> cardDeletedFuture = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, cardDeletedFuture));

        // Deleting the endpoint card over STOMP (the real, transactional mutation path) cascades
        // the connector away underneath — a subsequent CONNECTION_DELETE for that now-cascaded
        // id must still be a tolerant no-op, not an exception.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", cardA.getId().toString())));
        cardDeletedFuture.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        assertThat(cardConnectionRepository.findById(connectionId)).isEmpty();

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_DELETE",
                        "data", Map.of("id", connectionId.toString())));

        Thread.sleep(300);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 6 — a VIEWER cannot create or delete a connector
    // =========================================================================

    @Test
    void viewer_cannot_create_connection() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardA.getId().toString(), "toId", cardB.getId().toString())));

        Thread.sleep(300);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void viewer_cannot_delete_connection() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);
        CardConnection connection = cardConnectionRepository.save(
                new CardConnection(board.getId(), tenantId, cardA.getId(), cardB.getId(), Instant.now()));

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_DELETE", "data", Map.of("id", connection.getId().toString())));

        Thread.sleep(300);
        assertThat(cardConnectionRepository.findById(connection.getId())).isPresent();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 7 — a non-member of the board cannot mutate a connector (EN08.1 channel interceptor)
    // =========================================================================

    @Test
    void non_member_cannot_create_connection() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long outsiderId = seedUser(tenantId);
        String outsiderToken = issueToken(outsiderId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);

        // Outsider is authenticated (same tenant) but never added as a board member — the
        // WhiteboardChannelInterceptor's membership check denies the SEND frame before it ever
        // reaches CanvasActionService (EN08.1), independent of the connection-specific logic.
        StompSession session = connectAs(outsiderToken);
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CONNECTION_CREATE",
                        "data", Map.of("fromId", cardA.getId().toString(), "toId", cardB.getId().toString())));

        Thread.sleep(300);
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).isEmpty();
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
