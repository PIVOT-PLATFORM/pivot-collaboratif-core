package fr.pivot.collaboratif.whiteboard.vote;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the dot-voting STOMP contract (Vote / dot-voting feature) against a real
 * PostgreSQL + Redis via Testcontainers.
 *
 * <p>Verifies:
 * <ol>
 *   <li>The full cycle {@code vote:start} → {@code vote:cast} → {@code vote:uncast} →
 *       {@code vote:stop} with the exact echo names the frontend subscribes to
 *       ({@code vote:session:started}, {@code vote:updated}, {@code vote:session:closed}).</li>
 *   <li>The per-user quota is enforced (a cast past {@code votesPerPerson} is dropped).</li>
 *   <li>Dot stacking: a user may place several dots on the same card, up to the quota.</li>
 *   <li>{@code vote:uncast} removes exactly one voice.</li>
 *   <li>A VIEWER cannot start or stop a vote; any member may cast.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class VoteActionIT {

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
    private VoteSessionRepository voteSessionRepository;

    @Autowired
    private VoteRepository voteRepository;

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
    // Test 1 — full start → cast → uncast → stop cycle with exact echo names
    // =========================================================================

    @Test
    void full_cycle_uses_exact_echo_names_and_tallies_votes() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/whiteboard/" + board.getId(), queueHandler(queue));
        Thread.sleep(150);

        // vote:start (OWNER) → vote:session:started
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:start", "data", Map.of(
                        "boardId", board.getId().toString(),
                        "votesPerPerson", 3,
                        "timerSeconds", 120,
                        "voterIds", List.of(String.valueOf(ownerId)))));

        BroadcastCanvasMessage started = poll(queue);
        assertThat(started.type()).isEqualTo("vote:session:started");
        assertThat(started.data().get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) started.data().get("votesPerPerson")).intValue()).isEqualTo(3);
        assertThat(((Number) started.data().get("timerSeconds")).intValue()).isEqualTo(120);
        assertThat(started.data().get("timerEndsAt")).isNotNull();
        assertThat((List<?>) started.data().get("voterIds")).containsExactly(String.valueOf(ownerId));
        assertThat((List<?>) started.data().get("votes")).isEmpty();
        String sessionId = (String) started.data().get("id");

        // vote:cast → vote:updated, one dot
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:cast", "data", Map.of(
                        "sessionId", sessionId, "boardId", board.getId().toString(),
                        "cardId", card.getId().toString())));
        BroadcastCanvasMessage afterCast = poll(queue);
        assertThat(afterCast.type()).isEqualTo("vote:updated");
        List<?> votes = (List<?>) afterCast.data().get("votes");
        assertThat(votes).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstVote = (Map<String, Object>) votes.get(0);
        assertThat(firstVote.get("cardId")).isEqualTo(card.getId().toString());
        assertThat(firstVote.get("userId")).isEqualTo(String.valueOf(ownerId));
        assertThat(firstVote.get("sessionId")).isEqualTo(sessionId);

        // vote:uncast → vote:updated, back to zero
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:uncast", "data", Map.of(
                        "sessionId", sessionId, "boardId", board.getId().toString(),
                        "cardId", card.getId().toString())));
        BroadcastCanvasMessage afterUncast = poll(queue);
        assertThat(afterUncast.type()).isEqualTo("vote:updated");
        assertThat((List<?>) afterUncast.data().get("votes")).isEmpty();

        // vote:stop → vote:session:closed
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:stop", "data", Map.of(
                        "sessionId", sessionId, "boardId", board.getId().toString())));
        BroadcastCanvasMessage closed = poll(queue);
        assertThat(closed.type()).isEqualTo("vote:session:closed");
        assertThat(closed.data().get("status")).isEqualTo("CLOSED");
        assertThat(closed.data().get("closedAt")).isNotNull();

        assertThat(voteSessionRepository.findById(UUID.fromString(sessionId)))
                .get()
                .satisfies(s -> assertThat(s.getStatus()).isEqualTo(VoteStatus.CLOSED));
    }

    // =========================================================================
    // Test 2 — dot stacking up to the quota, then the over-quota cast is dropped
    // =========================================================================

    @Test
    void quota_is_enforced_and_dots_stack_on_the_same_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/whiteboard/" + board.getId(), queueHandler(queue));
        Thread.sleep(150);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:start", "data", Map.of(
                        "boardId", board.getId().toString(), "votesPerPerson", 2,
                        "voterIds", List.of(String.valueOf(ownerId)))));
        String sessionId = (String) poll(queue).data().get("id");

        // Two casts on the same card — dots stack (no unique(session,card,user) constraint).
        castAndAwait(session, board.getId(), sessionId, card.getId(), queue);
        castAndAwait(session, board.getId(), sessionId, card.getId(), queue);
        assertThat(voteRepository.countBySessionIdAndUserId(UUID.fromString(sessionId), ownerId)).isEqualTo(2);

        // Third cast is past the quota (2) — dropped, no broadcast, DB unchanged.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:cast", "data", Map.of(
                        "sessionId", sessionId, "boardId", board.getId().toString(),
                        "cardId", card.getId().toString())));
        Thread.sleep(400);
        assertThat(queue).isEmpty();
        assertThat(voteRepository.countBySessionIdAndUserId(UUID.fromString(sessionId), ownerId)).isEqualTo(2);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 3 — a VIEWER cannot start a vote; a VIEWER cannot stop one
    // =========================================================================

    @Test
    void viewer_cannot_start_or_stop_but_owner_can() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));

        // VIEWER start → dropped, nothing persisted.
        StompSession viewer = connectAs(issueToken(viewerId));
        viewer.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(150);
        viewer.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:start", "data", Map.of(
                        "boardId", board.getId().toString(), "votesPerPerson", 1, "voterIds", List.of())));
        Thread.sleep(400);
        assertThat(voteSessionRepository.existsByBoardIdAndStatus(board.getId(), VoteStatus.ACTIVE)).isFalse();

        // OWNER start succeeds; VIEWER stop is then dropped (session stays ACTIVE).
        StompSession owner = connectAs(issueToken(ownerId));
        BlockingQueue<BroadcastCanvasMessage> ownerQueue = new LinkedBlockingQueue<>();
        owner.subscribe("/topic/whiteboard/" + board.getId(), queueHandler(ownerQueue));
        Thread.sleep(150);
        owner.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:start", "data", Map.of(
                        "boardId", board.getId().toString(), "votesPerPerson", 1, "voterIds", List.of())));
        String sessionId = (String) poll(ownerQueue).data().get("id");

        viewer.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "vote:stop", "data", Map.of(
                        "sessionId", sessionId, "boardId", board.getId().toString())));
        Thread.sleep(400);
        assertThat(voteSessionRepository.findById(UUID.fromString(sessionId)))
                .get()
                .satisfies(s -> assertThat(s.getStatus()).isEqualTo(VoteStatus.ACTIVE));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void castAndAwait(
            final StompSession session, final UUID boardId, final String sessionId,
            final UUID cardId, final BlockingQueue<BroadcastCanvasMessage> queue) throws Exception {
        session.send("/app/whiteboard/" + boardId + "/action",
                Map.of("type", "vote:cast", "data", Map.of(
                        "sessionId", sessionId, "boardId", boardId.toString(),
                        "cardId", cardId.toString())));
        poll(queue);
    }

    private BroadcastCanvasMessage poll(final BlockingQueue<BroadcastCanvasMessage> queue) throws Exception {
        BroadcastCanvasMessage msg = queue.poll(5, TimeUnit.SECONDS);
        assertThat(msg).as("expected a broadcast within 5s").isNotNull();
        return msg;
    }

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
        Board board = new Board("Vote board", tenantId, ownerId, Instant.now());
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

    private StompFrameHandler queueHandler(final BlockingQueue<BroadcastCanvasMessage> queue) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return BroadcastCanvasMessage.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                queue.add((BroadcastCanvasMessage) payload);
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
