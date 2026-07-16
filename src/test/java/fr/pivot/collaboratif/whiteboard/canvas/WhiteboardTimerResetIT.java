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
import java.util.concurrent.TimeoutException;

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the whiteboard facilitation timer and the destructive STOMP board reset,
 * over a real STOMP connection and a real Redis (Testcontainers) — not a mocked broker.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@code timer:start} broadcasts a {@code timer:started} whose {@code endsAt}/{@code serverNow}
 *       are coherent (the interval equals the requested duration and lies in the future).</li>
 *   <li>{@code timer:stop} broadcasts {@code timer:stopped}.</li>
 *   <li>A client that JOINs while a timer runs is resynced with a {@code timer:started} carrying
 *       the same {@code endsAt}.</li>
 *   <li>{@code board:reset} by the OWNER empties cards + connectors and broadcasts
 *       {@code board:resetted}.</li>
 *   <li>{@code board:reset} by a non-OWNER (EDITOR) is refused — no broadcast, board untouched.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardTimerResetIT {

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
    // Timer
    // =========================================================================

    @Test
    void timer_start_broadcasts_coherent_endsAt() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(issueToken(ownerId));
        CompletableFuture<BroadcastCanvasMessage> started = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                typedHandler("timer:started", started));
        Thread.sleep(200);

        long beforeSend = System.currentTimeMillis();
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "timer:start", "data", Map.of("boardId", board.getId().toString(), "duration", 300)));

        BroadcastCanvasMessage msg = started.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("timer:started");
        assertThat(msg.boardId()).isEqualTo(board.getId().toString());
        long endsAt = ((Number) map(msg).get("endsAt")).longValue();
        long serverNow = ((Number) map(msg).get("serverNow")).longValue();
        // duration=300s → endsAt is exactly 300_000 ms after serverNow, and both are around "now".
        assertThat(endsAt - serverNow).isEqualTo(300_000L);
        assertThat(serverNow).isGreaterThanOrEqualTo(beforeSend);
        assertThat(endsAt).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void timer_stop_broadcasts_timer_stopped() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(issueToken(ownerId));
        CompletableFuture<BroadcastCanvasMessage> stopped = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                typedHandler("timer:stopped", stopped));
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "timer:start", "data", Map.of("boardId", board.getId().toString(), "duration", 120)));
        Thread.sleep(150);
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "timer:stop", "data", Map.of("boardId", board.getId().toString())));

        BroadcastCanvasMessage msg = stopped.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("timer:stopped");
        assertThat(msg.boardId()).isEqualTo(board.getId().toString());
    }

    @Test
    void joiner_receives_running_timer_on_join() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long editorId = seedUser(tenantId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), editorId), BoardRole.EDITOR, Instant.now()));

        // Owner starts a timer and captures the canonical endsAt.
        StompSession owner = connectAs(issueToken(ownerId));
        CompletableFuture<BroadcastCanvasMessage> ownerStarted = new CompletableFuture<>();
        owner.subscribe("/topic/whiteboard/" + board.getId(), typedHandler("timer:started", ownerStarted));
        Thread.sleep(200);
        owner.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "timer:start", "data", Map.of("boardId", board.getId().toString(), "duration", 600)));
        long canonicalEndsAt = ((Number) map(ownerStarted.get(5, TimeUnit.SECONDS)).get("endsAt")).longValue();

        // A late joiner subscribes, then JOINs, and must be resynced with the running timer.
        StompSession joiner = connectAs(issueToken(editorId));
        CompletableFuture<BroadcastCanvasMessage> joinerStarted = new CompletableFuture<>();
        joiner.subscribe("/topic/whiteboard/" + board.getId(), typedHandler("timer:started", joinerStarted));
        Thread.sleep(200);
        joiner.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "JOIN", "data", Map.of("displayName", "Bob")));

        BroadcastCanvasMessage msg = joinerStarted.get(5, TimeUnit.SECONDS);
        assertThat(((Number) map(msg).get("endsAt")).longValue()).isEqualTo(canonicalEndsAt);
        assertThat(map(msg).get("serverNow")).isNotNull();
    }

    // =========================================================================
    // Board reset
    // =========================================================================

    @Test
    void board_reset_by_owner_clears_board_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card cardA = seedCard(board.getId(), tenantId);
        Card cardB = seedCard(board.getId(), tenantId);
        cardConnectionRepository.save(
                new CardConnection(board.getId(), tenantId, cardA.getId(), cardB.getId(), Instant.now()));

        StompSession session = connectAs(issueToken(ownerId));
        CompletableFuture<BroadcastCanvasMessage> resetted = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                typedHandler("board:resetted", resetted));
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:reset", "data", Map.of("boardId", board.getId().toString())));

        BroadcastCanvasMessage msg = resetted.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("board:resetted");
        assertThat(msg.boardId()).isEqualTo(board.getId().toString());
        assertThat(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId))
                .isEmpty();
        assertThat(cardConnectionRepository.findAllByBoardIdAndTenantId(board.getId(), tenantId)).isEmpty();
    }

    @Test
    void board_reset_refused_for_non_owner() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long editorId = seedUser(tenantId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), editorId), BoardRole.EDITOR, Instant.now()));
        seedCard(board.getId(), tenantId);

        StompSession session = connectAs(issueToken(editorId));
        CompletableFuture<BroadcastCanvasMessage> resetted = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                typedHandler("board:resetted", resetted));
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:reset", "data", Map.of("boardId", board.getId().toString())));

        // No broadcast reaches the room, and the board is untouched.
        assertThatThrownBy(() -> resetted.get(1500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId))
                .hasSize(1);
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
        Board board = new Board("Timer/reset test", tenantId, ownerId, Instant.now());
        boardRepository.save(board);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, Instant.now()));
        return board;
    }

    private Card seedCard(final UUID boardId, final long tenantId) {
        return cardRepository.save(new Card(boardId, tenantId, CardType.TEXT, "seed", 0, 0, Instant.now()));
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

    /**
     * Frame handler that completes {@code future} only when a broadcast of the given wire
     * {@code type} arrives — the board topic multiplexes several message types (JOIN, board:state,
     * timer:started…), so a test waiting for one specific type must filter.
     *
     * @param type   the wire type to wait for
     * @param future the future to complete on the first matching frame
     * @return the filtering frame handler
     */
    private StompFrameHandler typedHandler(final String type, final CompletableFuture<BroadcastCanvasMessage> future) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return BroadcastCanvasMessage.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                BroadcastCanvasMessage msg = (BroadcastCanvasMessage) payload;
                if (type.equals(msg.type())) {
                    future.complete(msg);
                }
            }
        };
    }
}
