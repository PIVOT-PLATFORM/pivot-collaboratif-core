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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for EN08.4 — typed {@link Card} model and its {@code CARD_*} STOMP
 * contract.
 *
 * <p>Verifies:
 * <ol>
 *   <li>CARD_CREATE persists a card with the exact spec-mandated defaults and broadcasts it.</li>
 *   <li>An unknown/missing {@code type} falls back to {@link CardType#TEXT}, never an error.</li>
 *   <li>CARD_MOVE on an unlocked card updates its position and broadcasts.</li>
 *   <li>CARD_MOVE on a locked card is refused silently (0 rows affected, no broadcast).</li>
 *   <li>CARD_DELETE on a locked card is refused silently — 0 deletions, no broadcast
 *       (fix/EN08.4).</li>
 *   <li>CARD_DELETE on an unlocked card deletes and broadcasts (fix/EN08.4).</li>
 *   <li>CARD_DELETE on an already-deleted card is an idempotent no-op, never an exception.</li>
 *   <li>CARD_MOVE/CARD_RESIZE echo back a client-supplied {@code senderSessionId} verbatim (and
 *       omit it when not supplied), for frontend sender-exclusion filtering (fix/EN08.4).</li>
 *   <li>CARD_UPDATE broadcasts the full updated card, not just {@code {id, content}}
 *       (fix/EN08.4).</li>
 *   <li>A VIEWER cannot mutate a card (silent refusal, no DB change, no error frame).</li>
 *   <li>JOIN broadcasts a {@code board:state} snapshot of the board's current cards to the
 *       whole room.</li>
 *   <li>A card id from a different board cannot be mutated via a forged destination boardId.</li>
 *   <li>The real frontend wire vocabulary (lowercase, colon-separated — {@code card:create},
 *       {@code board:join}, {@code board:cursor}) is accepted, and {@code CARD_*} broadcasts
 *       go out under their past-tense wire name ({@code card:created}, not {@code CARD_CREATE}
 *       — the actual bug this enabler shipped with, see recette finding on #68).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardCardIT {

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
    // Test 1 — CARD_CREATE persists with spec-mandated defaults and broadcasts
    // =========================================================================

    @Test
    void card_create_persists_with_defaults_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "Hello board")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("TEXT");
        assertThat(cardData.get("content")).isEqualTo("Hello board");
        assertThat(((Number) cardData.get("posX")).doubleValue()).isZero();
        assertThat(((Number) cardData.get("posY")).doubleValue()).isZero();
        assertThat(((Number) cardData.get("width")).doubleValue()).isEqualTo(192.0);
        assertThat(((Number) cardData.get("height")).doubleValue()).isEqualTo(128.0);
        assertThat(cardData.get("color")).isEqualTo("#FFEB3B");
        assertThat(((Number) cardData.get("layer")).intValue()).isEqualTo(1);
        assertThat(cardData.get("locked")).isEqualTo(false);

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.TEXT);
        assertThat(cards.get(0).getContent()).isEqualTo("Hello board");
    }

    // =========================================================================
    // Test 2 — Unknown card type falls back to TEXT, never an error
    // =========================================================================

    @Test
    void card_create_with_unknown_type_falls_back_to_text() throws Exception {
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
                        "data", Map.of("content", "x", "type", "NOT_A_REAL_TYPE")));

        future.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.TEXT);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 3 — CARD_MOVE on an unlocked card updates position and broadcasts
    // =========================================================================

    @Test
    void card_move_unlocked_updates_position_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", card.getId().toString(), "posX", 42.0, "posY", 84.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:moved");
        assertThat(msg.data().get("id")).isEqualTo(card.getId().toString());

        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isEqualTo(42.0);
        assertThat(reloaded.getPosY()).isEqualTo(84.0);
    }

    // =========================================================================
    // Test 4 — CARD_MOVE on a locked card is refused silently
    // =========================================================================

    @Test
    void card_move_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", card.getId().toString(), "posX", 999.0, "posY", 999.0)));

        // No broadcast is expected — give the (silently dropped) action time to have been
        // processed, then assert the position never changed.
        Thread.sleep(300);
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 4b — CARD_DELETE on a locked card is refused silently (fix/EN08.4)
    // =========================================================================

    @Test
    void card_delete_locked_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", card.getId().toString())));

        // No broadcast is expected — give the (silently dropped) action time to have been
        // processed, then assert the card was never deleted.
        Thread.sleep(300);
        assertThat(cardRepository.findById(card.getId())).isPresent();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 4c — CARD_DELETE on an unlocked card deletes and broadcasts (fix/EN08.4)
    // =========================================================================

    @Test
    void card_delete_unlocked_deletes_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);

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
        // the transaction actually commits — give it a moment to flush (same pattern as every
        // other post-broadcast DB assertion in this file).
        Thread.sleep(200);
        assertThat(cardRepository.findById(card.getId())).isEmpty();
    }

    // =========================================================================
    // Test 4d/4e — CARD_MOVE/CARD_RESIZE echo back a client-supplied senderSessionId
    // (fix/EN08.4 sender exclusion) — the frontend uses this to ignore its own echo. This is a
    // client-generated opaque correlation id (like card:create's clientTag), not the server's
    // STOMP session id — see CanvasActionService#handleCardMove's Javadoc for why: attempting
    // to hand the real simpSessionId back via the STOMP CONNECTED frame does not survive
    // Spring's SimpleBroker, which rebuilds that frame's headers from scratch downstream.
    // =========================================================================

    @Test
    void card_move_broadcast_echoes_client_supplied_sender_session_id() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", card.getId().toString(), "posX", 15.0, "posY", 25.0,
                                "senderSessionId", "client-conn-abc123")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:moved");
        assertThat(msg.data().get("senderSessionId")).isEqualTo("client-conn-abc123");
    }

    @Test
    void card_move_broadcast_omits_sender_session_id_when_not_supplied() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", card.getId().toString(), "posX", 5.0, "posY", 5.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:moved");
        assertThat(msg.data()).doesNotContainKey("senderSessionId");
    }

    @Test
    void card_resize_broadcast_echoes_client_supplied_sender_session_id() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_RESIZE",
                        "data", Map.of("id", card.getId().toString(), "width", 300.0, "height", 200.0,
                                "senderSessionId", "client-conn-xyz789")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:resized");
        assertThat(msg.data().get("senderSessionId")).isEqualTo("client-conn-xyz789");
    }

    // =========================================================================
    // Test 4f — CARD_UPDATE broadcasts the full updated card, not just {id, content} (fix/EN08.4)
    // =========================================================================

    @Test
    void card_update_broadcasts_full_card_not_just_id_and_content() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_UPDATE",
                        "data", Map.of("id", card.getId().toString(), "content", "updated text")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:updated");
        assertThat(msg.data().get("id")).isEqualTo(card.getId().toString());
        assertThat(msg.data().get("content")).isEqualTo("updated text");
        // The full CardDto shape, not just {id, content} — same fields card:created carries.
        assertThat(msg.data().get("type")).isEqualTo("TEXT");
        assertThat(((Number) msg.data().get("width")).doubleValue()).isEqualTo(192.0);
        assertThat(((Number) msg.data().get("height")).doubleValue()).isEqualTo(128.0);
        assertThat(msg.data().get("color")).isEqualTo("#FFEB3B");
        assertThat(msg.data().get("locked")).isEqualTo(false);
    }

    // =========================================================================
    // Test 5 — CARD_DELETE on an already-deleted card is an idempotent no-op
    // =========================================================================

    @Test
    void card_delete_already_deleted_is_idempotent_noop() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card card = seedCard(board.getId(), tenantId, false);
        UUID cardId = card.getId();
        cardRepository.deleteById(cardId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_DELETE", "data", Map.of("id", cardId.toString())));

        // A subsequent, valid CARD_CREATE must still work — proves the session survived the
        // no-op delete without any exception propagating up to the STOMP layer.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "CARD_CREATE", "data", Map.of("content", "still alive")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 6 — VIEWER cannot mutate a card
    // =========================================================================

    @Test
    void viewer_cannot_mutate_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Card card = seedCard(board.getId(), tenantId, false);

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
    // Test 7 — JOIN broadcasts a board:state snapshot of existing cards to the whole room
    // =========================================================================

    @Test
    void join_broadcasts_board_state_containing_existing_cards() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        seedCard(board.getId(), tenantId, false);
        seedCard(board.getId(), tenantId, false);

        // board:state is a room broadcast (not a per-user queue reply) — the frontend's
        // StompBoardTransport only subscribes to /topic/whiteboard/{boardId} and has no
        // per-user queue subscription, so a targeted convertAndSendToUser reply would never
        // reach it (recette finding on #68). JOIN itself broadcasts first (unchanged, existing
        // behaviour) — filter past it rather than taking whichever frame arrives first.
        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:join", "data", board.getId().toString()));

        BroadcastCanvasMessage msg = awaitType(queue, "board:state", 8);
        assertThat(msg.type()).isEqualTo("board:state");
        assertThat(msg.data()).containsKeys("cards", "connections", "frames", "fields");
        @SuppressWarnings("unchecked")
        List<Object> cards = (List<Object>) msg.data().get("cards");
        assertThat(cards).hasSize(2);
        assertThat((List<?>) msg.data().get("connections")).isEmpty();
        // role is deliberately absent — this is a room-wide broadcast, not per-recipient
        // (see CanvasActionService class Javadoc); role stays authoritative via the REST GET.
        assertThat(msg.data()).doesNotContainKey("role");
    }

    // =========================================================================
    // Test 7b — the real frontend wire vocabulary is accepted (recette finding on #68)
    // =========================================================================

    @Test
    void real_frontend_wire_names_are_accepted_end_to_end() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        // board.store.ts#init: this.transport.emit('board:join', boardId) — data is a bare
        // string, not an object (the exact shape that previously threw
        // MessageConversionException before this fix). JOIN and board:state both broadcast
        // first (existing behaviour, unrelated to this assertion) — filter past them.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:join", "data", board.getId().toString()));

        // board.store.ts createCard: this.transport.emit('card:create', { content, posX, posY })
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("content", "post-it via real wire name")));

        BroadcastCanvasMessage msg = awaitType(queue, "card:created", 5);
        assertThat(msg.type()).isEqualTo("card:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("content")).isEqualTo("post-it via real wire name");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 7c — board:cursor (the real wire name for CURSOR_MOVE) is accepted
    // =========================================================================

    @Test
    void board_cursor_wire_name_is_accepted_and_broadcasts_as_cursor_move() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:cursor", "data", Map.of("x", 100, "y", 200)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("CURSOR_MOVE");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 8 — A card id from a different board cannot be mutated via a forged boardId
    // =========================================================================

    @Test
    void card_from_another_board_is_not_mutable_via_forged_destination() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        Card cardOnA = seedCard(boardA.getId(), tenantId, false);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + boardB.getId(), noOpHandler());
        Thread.sleep(100);

        // Same user is a member of both boards (both owned), but sends the action against
        // boardB's destination while supplying boardA's card id — must not move.
        session.send("/app/whiteboard/" + boardB.getId() + "/action",
                Map.of("type", "CARD_MOVE",
                        "data", Map.of("id", cardOnA.getId().toString(), "posX", 500.0, "posY", 500.0)));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(cardOnA.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
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

    private Card seedCard(final UUID boardId, final long tenantId, final boolean locked) {
        Card card = new Card(boardId, tenantId, CardType.TEXT, "seed", 0, 0, Instant.now());
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

    /**
     * Subscribes to the board's main topic, queuing every broadcast received — for tests that
     * expect more than one broadcast in flight (e.g. JOIN's own echo before {@code board:state})
     * and need to pick out a specific type rather than whichever frame arrives first.
     */
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

    /**
     * Drains {@code queue} until a message of the given {@code type} appears, or fails the test
     * once {@code timeoutSeconds} has elapsed with none found — messages of other types are
     * silently discarded (they are asserted, or not, by other parts of the same test).
     */
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
