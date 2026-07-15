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

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US08.6.5's SSRF hardening and the LINK/TEXT/LABEL enrichment hand-off —
 * exercised against the <strong>real</strong> {@code DefaultSsrfGuard} bean (no test double), so
 * these tests prove the production guard genuinely rejects private/loopback/link-local/cloud-
 * metadata targets end-to-end over the real STOMP pipeline, not just at the unit level (see
 * {@code DefaultSsrfGuardTest} for the isolated unit coverage of the blocklist logic itself).
 *
 * <p>Fetch-mechanics coverage (nominal fetch, redirects, timeout, content-type, body cap) lives
 * in {@code WhiteboardLinkFetchIT}, which substitutes a permissive {@code SsrfGuard} test double
 * so it can point at a local test HTTP server — loopback would otherwise always be rejected by
 * the real guard exercised here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardLinkSsrfIT {

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
    // SSRF: loopback, cloud metadata, RFC 1918, non-http scheme — all refused silently
    // =========================================================================

    @Test
    void link_card_pointing_at_loopback_is_never_enriched() throws Exception {
        assertNoEnrichmentHappens("http://127.0.0.1:1/should-never-be-fetched");
    }

    @Test
    void link_card_pointing_at_cloud_metadata_endpoint_is_never_enriched() throws Exception {
        assertNoEnrichmentHappens("http://169.254.169.254/latest/meta-data/");
    }

    @Test
    void link_card_pointing_at_rfc1918_private_address_is_never_enriched() throws Exception {
        assertNoEnrichmentHappens("http://10.255.255.1/");
    }

    @Test
    void link_card_with_non_http_scheme_is_never_enriched() throws Exception {
        assertNoEnrichmentHappens("file:///etc/passwd");
    }

    @Test
    void text_card_with_ssrf_target_url_is_never_enriched() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(150);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create",
                        "data", Map.of("type", "TEXT", "content", "danger: http://127.0.0.1/secret")));

        Thread.sleep(500);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getMeta()).isNull();
        assertThat(session.isConnected()).isTrue();
    }

    /**
     * Creates a LINK card pointing at an SSRF-blocked target and asserts: the card is created
     * normally (only the async enrichment is refused), no {@code card:meta_updated} broadcast
     * ever arrives, the persisted {@code meta} stays {@code null}, and the STOMP session is
     * unaffected (no exception propagated).
     */
    private void assertNoEnrichmentHappens(final String blockedUrl) throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> metaUpdated = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return BroadcastCanvasMessage.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                BroadcastCanvasMessage msg = (BroadcastCanvasMessage) payload;
                if ("card:meta_updated".equals(msg.type())) {
                    metaUpdated.complete(msg);
                }
            }
        });
        Thread.sleep(150);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create",
                        "data", Map.of("type", "LINK", "content", blockedUrl)));

        // No card:meta_updated must ever arrive — assert its absence by timing out.
        assertThatFutureNeverCompletes(metaUpdated);

        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo(CardType.LINK);
        assertThat(cards.get(0).getContent()).isEqualTo(blockedUrl);
        assertThat(cards.get(0).getMeta()).isNull();
        assertThat(session.isConnected()).isTrue();
    }

    private void assertThatFutureNeverCompletes(final CompletableFuture<BroadcastCanvasMessage> future)
            throws Exception {
        try {
            BroadcastCanvasMessage unexpected = future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected no card:meta_updated broadcast, but got: " + unexpected);
        } catch (java.util.concurrent.TimeoutException expected) {
            // Absence confirmed within the wait budget — the desired outcome.
        }
    }

    // =========================================================================
    // VIEWER cannot create a LINK card (reuses EN08.4's generic card-mutation guard)
    // =========================================================================

    @Test
    void viewer_cannot_create_link_card() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        Thread.sleep(150);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create",
                        "data", Map.of("type", "LINK", "content", "https://example.com")));

        Thread.sleep(300);
        List<Card> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(cards).isEmpty();
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // card:update removing the URL resets meta to null and broadcasts it (parity spec AC4)
    // =========================================================================

    @Test
    void card_update_removing_url_resets_meta_to_null_and_broadcasts() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        // Seed a card that already has a preview cached — bypasses the real fetch entirely
        // (this test is only about the "URL removed -> meta cleared" contract, not the fetch
        // itself, which is covered by WhiteboardLinkFetchIT).
        Card card = new Card(board.getId(), tenantId, CardType.LINK, "https://example.com/old", 0, 0, Instant.now());
        card.setMeta("{\"title\":\"Old title\",\"description\":null,\"image\":null,\"siteName\":null}");
        cardRepository.save(card);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> metaUpdated = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return BroadcastCanvasMessage.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                BroadcastCanvasMessage msg = (BroadcastCanvasMessage) payload;
                if ("card:meta_updated".equals(msg.type())) {
                    metaUpdated.complete(msg);
                }
            }
        });

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:update",
                        "data", Map.of("id", card.getId().toString(), "content", "no more link here")));

        BroadcastCanvasMessage msg = metaUpdated.get(5, TimeUnit.SECONDS);
        assertThat(map(msg).get("id")).isEqualTo(card.getId().toString());
        assertThat(map(msg).get("meta")).isNull();

        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getMeta()).isNull();
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
