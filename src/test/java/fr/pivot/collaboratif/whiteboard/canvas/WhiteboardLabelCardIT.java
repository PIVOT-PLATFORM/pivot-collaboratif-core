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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.6.2 — the {@code LABEL} {@link CardType} value exercised through
 * the generic {@code CARD_*} STOMP contract already implemented by EN08.4 (see
 * {@link WhiteboardCardIT} for the equivalent TEXT-focused coverage this US mirrors).
 *
 * <p>This US is additive: it does not reimplement the generic card lifecycle, it validates
 * that {@code type=LABEL} flows through unchanged (never coerced back to {@code TEXT}, common
 * defaults applied, lock guard enforced on every mutation including delete) and closes one
 * genuine gap found while implementing it — {@code card:delete} did not previously read
 * {@code locked} at all (see the updated {@link CanvasActionService#handleCardDelete}).
 *
 * <p><strong>Known, deliberately not fixed here:</strong> the parity spec's "move/resize
 * broadcast excludes the emitter" asymmetry is not implemented at the EN08.4 broadcast layer —
 * {@link CanvasActionService#broadcast} sends to the whole room unconditionally for every
 * {@code CARD_*} type, a pre-existing behaviour already asserted this way for TEXT in {@link
 * WhiteboardCardIT#card_move_unlocked_updates_position_and_broadcasts()}. Fixing that would
 * touch the shared broadcast helper used by all six card-type US in flight this sprint; it is
 * flagged to the maintainer for centralised arbitration rather than changed unilaterally from
 * this LABEL-only PR (see this US's Gate 1/PR notes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardLabelCardIT {

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
    // AC1/AC2 — CARD_CREATE with type=LABEL: type preserved, common defaults applied
    // =========================================================================

    @Test
    void label_create_preserves_type_and_applies_common_defaults() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE",
                        "data", Map.of("type", "LABEL", "content", "Sprint 12", "posX", 10.0, "posY", 20.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("LABEL");
        assertThat(cardData.get("content")).isEqualTo("Sprint 12");
        assertThat(((Number) cardData.get("width")).doubleValue()).isEqualTo(192.0);
        assertThat(((Number) cardData.get("height")).doubleValue()).isEqualTo(128.0);
        assertThat(cardData.get("color")).isEqualTo("#FFEB3B");
        assertThat(((Number) cardData.get("layer")).intValue()).isEqualTo(1);
        assertThat(cardData.get("locked")).isEqualTo(false);

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.LABEL);
        assertThat(cards.get(0).getContent()).isEqualTo("Sprint 12");
    }

    // =========================================================================
    // AC3 — move/resize on an unlocked LABEL; locked LABEL is refused silently
    // =========================================================================

    @Test
    void label_move_unlocked_updates_position_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", card.getId().toString(), "posX", 42.0, "posY", 84.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:moved");
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isEqualTo(42.0);
        assertThat(reloaded.getPosY()).isEqualTo(84.0);
    }

    @Test
    void label_resize_unlocked_updates_size_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RESIZE",
                        "data", Map.of("id", card.getId().toString(), "width", 220.0, "height", 60.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:resized");
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getWidth()).isEqualTo(220.0);
        assertThat(reloaded.getHeight()).isEqualTo(60.0);
    }

    @Test
    void label_move_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", card.getId().toString(), "posX", 999.0, "posY", 999.0)));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // AC4 — update on unlocked LABEL, empty content persisted as-is (no server rejection)
    // =========================================================================

    @Test
    void label_update_with_empty_content_is_persisted_as_is_and_broadcast_to_whole_room() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_UPDATE", "data", Map.of("id", card.getId().toString(), "content", "")));

        // update:content broadcasts to the whole room, sender included — the sender's own
        // subscribed session receiving this frame is exactly that assertion.
        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:updated");
        assertThat(msg.data().get("content")).isEqualTo("");

        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEmpty();
    }

    @Test
    void label_update_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_UPDATE",
                        "data", Map.of("id", card.getId().toString(), "content", "should not apply")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getContent()).isEqualTo("seed");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // AC5 — recolor on unlocked LABEL
    // =========================================================================

    @Test
    void label_recolor_unlocked_updates_color_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RECOLOR",
                        "data", Map.of("id", card.getId().toString(), "color", "#00FF00")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:recolored");
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getColor()).isEqualTo("#00FF00");
    }

    // =========================================================================
    // AC6 — delete: explicit locked read (gap closed by this US), idempotent absence
    // =========================================================================

    @Test
    void label_delete_locked_is_refused_silently_and_card_survives() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", card.getId().toString())));

        Thread.sleep(300);
        Optional<Card> reloaded = cardRepository.findById(card.getId());
        assertThat(reloaded).isPresent();
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void label_delete_unlocked_deletes_and_broadcasts_raw_id() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedLabelCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", card.getId().toString())));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:deleted");
        assertThat(msg.data().get("id")).isEqualTo(card.getId().toString());

        // The broadcast is sent from inside handleCardDelete's @Transactional method, before
        // the transaction actually commits — give it a moment to flush, same pattern as
        // WhiteboardCardIT's card creation/deletion assertions.
        Thread.sleep(200);
        assertThat(cardRepository.findById(card.getId())).isEmpty();
    }

    @Test
    void label_delete_nonexistent_is_idempotent_noop() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        UUID neverExisted = UUID.randomUUID();

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", neverExisted.toString())));

        // A subsequent, valid CARD_CREATE must still work — proves the no-op delete never
        // propagated an exception up the STOMP session.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("type", "LABEL", "content", "still alive")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Error case — cross-board id is never mutable via a forged destination
    // =========================================================================

    @Test
    void label_card_from_another_board_is_not_deletable_via_forged_destination() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        Card cardOnA = seedLabelCard(boardA.getId(), tenantId, false);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + boardB.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + boardB.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", cardOnA.getId().toString())));

        Thread.sleep(300);
        assertThat(cardRepository.findById(cardOnA.getId())).isPresent();
    }

    // =========================================================================
    // Security — VIEWER cannot mutate a LABEL card
    // =========================================================================

    @Test
    void viewer_cannot_mutate_label_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Card card = seedLabelCard(board.getId(), tenantId, false);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RECOLOR",
                        "data", Map.of("id", card.getId().toString(), "color", "#000000")));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
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

    private Card seedLabelCard(final UUID boardId, final long tenantId, final boolean locked) {
        Card card = new Card(boardId, tenantId, CardType.LABEL, "seed", 0, 0, Instant.now());
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
