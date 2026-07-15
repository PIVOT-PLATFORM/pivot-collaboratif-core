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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.6.4 — {@code IMAGE} cards.
 *
 * <p>The generic {@code card:*} lifecycle (create/move/resize/delete, {@code locked} guard,
 * board/tenant scoping) is already covered by {@link WhiteboardCardIT} (EN08.4) — this class
 * only exercises the behaviour specific to the {@code IMAGE} type: server-side content
 * validation ({@link ImageCardContentValidator}), the locked-card guard applied to an IMAGE
 * card specifically, a VIEWER's silent refusal to create one, and tenant isolation of the
 * inline image content.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardImageCardIT {

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

    /** A minimal, real PNG signature — enough for {@link ImageCardContentValidator} to sniff. */
    private static final String VALID_PNG_DATA_URL = "data:image/png;base64," + Base64.getEncoder().encodeToString(
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03});

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
    // AC — create type=IMAGE with valid content persists an IMAGE card
    // =========================================================================

    @Test
    void card_create_with_valid_image_content_persists_as_image_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create",
                        "data", Map.of("type", "IMAGE", "content", VALID_PNG_DATA_URL, "width", 350.0, "height", 200.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("card:created");
        @SuppressWarnings("unchecked")
        Map<String, Object> cardData = (Map<String, Object>) msg.data().get("card");
        assertThat(cardData.get("type")).isEqualTo("IMAGE");
        assertThat((String) cardData.get("content")).startsWith("data:image/png;base64,");

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.IMAGE);
    }

    // =========================================================================
    // AC Security — server-side MIME/size validation rejects an invalid IMAGE
    // =========================================================================

    @Test
    void card_create_with_invalid_image_content_is_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        // Not a real image signature — plain text disguised as a PNG data URL.
        String fakeContent = "data:image/png;base64," + Base64.getEncoder().encodeToString(
                "not an actual image".getBytes(StandardCharsets.UTF_8));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("type", "IMAGE", "content", fakeContent)));

        // A subsequent, valid CARD_CREATE must still work — proves the session survived the
        // refusal without any exception propagating up to the STOMP layer.
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("content", "still alive")));
        future.get(5, TimeUnit.SECONDS);

        Thread.sleep(200);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.TEXT);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // AC — move/resize on a locked IMAGE card is refused silently (0 mutation)
    // =========================================================================

    @Test
    void move_and_resize_on_a_locked_image_card_are_refused_silently() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Card lockedImage = seedImageCard(board.getId(), tenantId, true);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:move",
                        "data", Map.of("id", lockedImage.getId().toString(), "posX", 500.0, "posY", 500.0)));
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:resize",
                        "data", Map.of("id", lockedImage.getId().toString(), "width", 999.0, "height", 999.0)));

        Thread.sleep(300);
        Card reloaded = cardRepository.findById(lockedImage.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
        assertThat(reloaded.getWidth()).isEqualTo(320.0);
        assertThat(reloaded.getHeight()).isEqualTo(240.0);
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // AC Security — a VIEWER cannot create an IMAGE card
    // =========================================================================

    @Test
    void viewer_cannot_create_an_image_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("type", "IMAGE", "content", VALID_PNG_DATA_URL)));

        Thread.sleep(300);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // AC Security — tenant isolation of inline image content
    // =========================================================================

    @Test
    void a_tenants_image_content_is_never_visible_to_another_tenants_board_state() throws Exception {
        long tenantA = seedTenant();
        long ownerA = seedUser(tenantA);
        Board boardA = createBoardWithOwner(tenantA, ownerA);
        seedImageCard(boardA.getId(), tenantA, false);

        long tenantB = seedTenant();
        long ownerB = seedUser(tenantB);
        Board boardB = createBoardWithOwner(tenantB, ownerB);

        List<Card> boardBCards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardB.getId(), tenantB);
        assertThat(boardBCards).isEmpty();

        // Even a forged cross-tenant lookup (boardA's id, tenantB's id) yields nothing — the
        // repository query scopes by both, matching CardRepository's Javadoc contract.
        List<Card> crossTenantAttempt = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardA.getId(), tenantB);
        assertThat(crossTenantAttempt).isEmpty();
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

    private Card seedImageCard(final UUID boardId, final long tenantId, final boolean locked) {
        Card card = new Card(boardId, tenantId, CardType.IMAGE, VALID_PNG_DATA_URL, 0, 0, Instant.now());
        card.setWidth(320);
        card.setHeight(240);
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
