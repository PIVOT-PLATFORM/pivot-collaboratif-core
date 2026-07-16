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
 * Integration tests for the custom card field value ({@link CardFieldValue}) STOMP handlers
 * (US08.10.2): {@code cardfield:set}/{@code cardfield:clear} and their flat
 * {@code cardfield:updated}/{@code cardfield:cleared} broadcasts, mirroring
 * {@link WhiteboardBoardFieldIT}'s Testcontainers/STOMP setup.
 *
 * <p>Covers the acceptance criteria:
 * <ul>
 *   <li>{@code cardfield:set} on an existing {@code (card, field)} → row persisted +
 *       {@code cardfield:updated} flat broadcast {@code {id, cardId, fieldId, value}};</li>
 *   <li>re-set on the same {@code (card, field)} → value replaced, still exactly one row (upsert,
 *       no duplicate);</li>
 *   <li><strong>set with a non-existent card or field id → FK violation tolerated: no exception,
 *       session stays connected, no {@code cardfield:updated} broadcast, no row</strong> (§3.9);</li>
 *   <li>{@code cardfield:clear} removes the value and broadcasts {@code cardfield:cleared}
 *       {@code {cardId, fieldId}}; clear when absent → still broadcasts (unconditional), no error;</li>
 *   <li>a VIEWER emitting {@code cardfield:set} is silently refused (no row, session stays
 *       connected);</li>
 *   <li>{@code board:state} on JOIN carries a previously-set value in the card's
 *       {@code fieldValues} (late-joiner replay).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardCardFieldValueIT {

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
    // cardfield:set → cardfield:updated (flat), value persisted
    // =========================================================================

    @Test
    void set_creates_value_and_echoes_flat_cardfield_updated() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = seedField(board.getId(), tenantId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:set",
                        "data", Map.of("cardId", card.getId().toString(),
                                "fieldId", field.getId().toString(), "value", "In progress")));

        BroadcastCanvasMessage msg = awaitType(queue, "cardfield:updated", 8);
        Map<String, Object> data = map(msg);
        assertThat(data.get("id")).isNotNull();
        assertThat(data.get("cardId")).isEqualTo(card.getId().toString());
        assertThat(data.get("fieldId")).isEqualTo(field.getId().toString());
        assertThat(data.get("value")).isEqualTo("In progress");

        Thread.sleep(200);
        CardFieldValue persisted = cardFieldValueRepository
                .findByCardIdAndFieldId(card.getId(), field.getId()).orElseThrow();
        assertThat(persisted.getValue()).isEqualTo("In progress");
        assertThat(persisted.getId().toString()).isEqualTo(data.get("id"));
    }

    // =========================================================================
    // Re-set on the same (card, field) → value replaced, exactly one row (upsert)
    // =========================================================================

    @Test
    void reset_on_same_pair_replaces_value_without_duplicate_row() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = seedField(board.getId(), tenantId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:set",
                        "data", Map.of("cardId", card.getId().toString(),
                                "fieldId", field.getId().toString(), "value", "Todo")));
        awaitType(queue, "cardfield:updated", 8);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:set",
                        "data", Map.of("cardId", card.getId().toString(),
                                "fieldId", field.getId().toString(), "value", "Done")));
        BroadcastCanvasMessage second = awaitType(queue, "cardfield:updated", 8);
        assertThat(map(second).get("value")).isEqualTo("Done");

        Thread.sleep(200);
        assertThat(cardFieldValueRepository.findAll().stream()
                .filter(v -> v.getCardId().equals(card.getId()) && v.getFieldId().equals(field.getId()))
                .toList())
                .hasSize(1);
        assertThat(cardFieldValueRepository
                .findByCardIdAndFieldId(card.getId(), field.getId()).orElseThrow().getValue())
                .isEqualTo("Done");
    }

    // =========================================================================
    // §3.9 — set with a non-existent card/field: FK violation tolerated silently
    // =========================================================================

    @Test
    void set_with_nonexistent_card_or_field_is_tolerated_without_broadcast() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        UUID ghostCard = UUID.randomUUID();
        UUID ghostField = UUID.randomUUID();

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:set",
                        "data", Map.of("cardId", ghostCard.toString(),
                                "fieldId", ghostField.toString(), "value", "orphan")));

        assertNoType(queue, "cardfield:updated", 2);
        assertThat(cardFieldValueRepository.findByCardIdAndFieldId(ghostCard, ghostField)).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // cardfield:clear → cardfield:cleared {cardId, fieldId}, row removed
    // =========================================================================

    @Test
    void clear_removes_value_and_broadcasts_cardfield_cleared() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = seedField(board.getId(), tenantId);
        Card card = seedCard(board.getId(), tenantId);
        cardFieldValueRepository.save(new CardFieldValue(card.getId(), field.getId(), "hello"));

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:clear",
                        "data", Map.of("cardId", card.getId().toString(),
                                "fieldId", field.getId().toString())));

        BroadcastCanvasMessage msg = awaitType(queue, "cardfield:cleared", 8);
        Map<String, Object> data = map(msg);
        assertThat(data.get("cardId")).isEqualTo(card.getId().toString());
        assertThat(data.get("fieldId")).isEqualTo(field.getId().toString());

        Thread.sleep(200);
        assertThat(cardFieldValueRepository.findByCardIdAndFieldId(card.getId(), field.getId())).isEmpty();
    }

    // =========================================================================
    // cardfield:clear when absent → still broadcasts unconditionally (§3.9), no error
    // =========================================================================

    @Test
    void clear_when_absent_still_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = seedField(board.getId(), tenantId);
        Card card = seedCard(board.getId(), tenantId);
        // No value ever set on (card, field).

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:clear",
                        "data", Map.of("cardId", card.getId().toString(),
                                "fieldId", field.getId().toString())));

        BroadcastCanvasMessage msg = awaitType(queue, "cardfield:cleared", 8);
        assertThat(map(msg).get("cardId")).isEqualTo(card.getId().toString());
        assertThat(map(msg).get("fieldId")).isEqualTo(field.getId().toString());
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Security — a VIEWER cannot set a card field value (silent refusal)
    // =========================================================================

    @Test
    void viewer_cannot_set_card_field_value() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        BoardField field = seedField(board.getId(), tenantId);
        Card card = seedCard(board.getId(), tenantId);

        StompSession session = connectAs(viewerToken);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "cardfield:set",
                        "data", Map.of("cardId", card.getId().toString(),
                                "fieldId", field.getId().toString(), "value", "Sneaky")));

        assertNoType(queue, "cardfield:updated", 2);
        assertThat(cardFieldValueRepository.findByCardIdAndFieldId(card.getId(), field.getId())).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // board:state on JOIN carries a previously-set value in the card's fieldValues
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void board_state_carries_field_value_for_late_joiner() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        BoardField field = seedField(board.getId(), tenantId);
        Card card = seedCard(board.getId(), tenantId);
        cardFieldValueRepository.save(new CardFieldValue(card.getId(), field.getId(), "In progress"));

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:join", "data", board.getId().toString()));

        BroadcastCanvasMessage msg = awaitType(queue, "board:state", 8);
        List<Object> cards = (List<Object>) map(msg).get("cards");
        assertThat(cards).hasSize(1);
        Map<String, Object> cardData = (Map<String, Object>) cards.get(0);
        assertThat(cardData.get("id")).isEqualTo(card.getId().toString());
        List<Object> fieldValues = (List<Object>) cardData.get("fieldValues");
        assertThat(fieldValues).hasSize(1);
        Map<String, Object> fv = (Map<String, Object>) fieldValues.get(0);
        assertThat(fv.get("cardId")).isEqualTo(card.getId().toString());
        assertThat(fv.get("fieldId")).isEqualTo(field.getId().toString());
        assertThat(fv.get("value")).isEqualTo("In progress");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BoardField seedField(final UUID boardId, final long tenantId) {
        return boardFieldRepository.save(new BoardField(
                boardId, tenantId, "Status", null, FieldType.TEXT, null, 0, Instant.now()));
    }

    private Card seedCard(final UUID boardId, final long tenantId) {
        return cardRepository.save(
                new Card(boardId, tenantId, CardType.TEXT, "seed", 0, 0, Instant.now()));
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
