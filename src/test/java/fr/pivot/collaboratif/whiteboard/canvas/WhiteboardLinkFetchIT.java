package fr.pivot.collaboratif.whiteboard.canvas;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.opengraph.SsrfGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the OpenGraph fetch mechanics (US08.6.5, parity spec §7 caps): nominal
 * fetch, redirect chain within/beyond the 5-redirect cap, non-2xx status, wrong content-type,
 * and a slow server exceeding the 5000&nbsp;ms timeout.
 *
 * <p>{@link SsrfGuard} is replaced with a permissive Mockito mock ({@code
 * @MockitoBean} — an unstubbed mock's {@code void validate(...)} is a no-op by default) purely
 * so these tests can point at a local loopback test server; the <strong>real</strong> {@code
 * DefaultSsrfGuard} rejecting loopback/private/link-local targets is covered end-to-end,
 * unmocked, by {@code WhiteboardLinkSsrfIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardLinkFetchIT {

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

    @MockitoBean
    private SsrfGuard ssrfGuard;

    private static HttpServer stubServer;
    private static int stubPort;

    private final List<StompSession> openSessions = new ArrayList<>();

    @BeforeAll
    static void startStubServer() throws Exception {
        stubServer = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        stubServer.createContext("/nominal", htmlHandler(nominalHtml(), "text/html"));
        stubServer.createContext("/wrong-content-type", htmlHandler("{\"not\":\"html\"}", "application/json"));
        stubServer.createContext("/not-found", exchange -> respond(exchange, 404, "gone", "text/html"));
        stubServer.createContext("/slow", exchange -> {
            try {
                Thread.sleep(6_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, nominalHtml(), "text/html");
        });
        stubServer.createContext("/long-description", htmlHandler(
                "<meta property=\"og:description\" content=\"" + "d".repeat(400) + "\">", "text/html"));
        stubServer.createContext("/oversized", exchange -> {
            StringBuilder body = new StringBuilder(
                    "<meta property=\"og:title\" content=\"Big page\">"
                            + "<meta property=\"og:description\" content=\"Still readable\">");
            body.append("<!-- padding -->".repeat(20_000));
            respond(exchange, 200, body.toString(), "text/html");
        });
        stubServer.createContext("/redirect/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int remaining = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            if (remaining <= 0) {
                respond(exchange, 200, nominalHtml(), "text/html");
                return;
            }
            exchange.getResponseHeaders().add("Location", "/redirect/" + (remaining - 1));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterAll
    static void stopStubServer() {
        stubServer.stop(0);
    }

    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
    }

    private static String nominalHtml() {
        return "<html><head>"
                + "<meta property=\"og:title\" content=\"Stub Page\">"
                + "<meta property=\"og:description\" content=\"A stub description.\">"
                + "<meta property=\"og:image\" content=\"https://cdn.example.com/stub.png\">"
                + "<meta property=\"og:site_name\" content=\"Stub Site\">"
                + "</head><body></body></html>";
    }

    private static HttpHandler htmlHandler(final String body, final String contentType) {
        return exchange -> respond(exchange, 200, body, contentType);
    }

    private static void respond(
            final HttpExchange exchange, final int status, final String body, final String contentType)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // =========================================================================
    // Nominal fetch — all four OG fields broadcast and persisted
    // =========================================================================

    @Test
    void nominal_fetch_broadcasts_all_four_fields() throws Exception {
        BroadcastCanvasMessage msg = createLinkAndAwaitMetaUpdated("http://localhost:" + stubPort + "/nominal");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) map(msg).get("meta");
        assertThat(meta.get("title")).isEqualTo("Stub Page");
        assertThat(meta.get("description")).isEqualTo("A stub description.");
        assertThat(meta.get("image")).isEqualTo("https://cdn.example.com/stub.png");
        assertThat(meta.get("siteName")).isEqualTo("Stub Site");
    }

    // =========================================================================
    // Redirects — within the 5-hop cap succeeds, beyond it is absorbed
    // =========================================================================

    @Test
    void redirect_chain_within_cap_succeeds() throws Exception {
        BroadcastCanvasMessage msg = createLinkAndAwaitMetaUpdated("http://localhost:" + stubPort + "/redirect/3");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) map(msg).get("meta");
        assertThat(meta.get("title")).isEqualTo("Stub Page");
    }

    @Test
    void redirect_chain_beyond_cap_is_absorbed() throws Exception {
        assertNoMetaUpdated("http://localhost:" + stubPort + "/redirect/9");
    }

    // =========================================================================
    // Non-2xx status and wrong content-type — absorbed
    // =========================================================================

    @Test
    void non_2xx_status_is_absorbed() throws Exception {
        assertNoMetaUpdated("http://localhost:" + stubPort + "/not-found");
    }

    @Test
    void wrong_content_type_is_absorbed() throws Exception {
        assertNoMetaUpdated("http://localhost:" + stubPort + "/wrong-content-type");
    }

    // =========================================================================
    // Timeout — a server slower than 5000 ms is absorbed
    // =========================================================================

    @Test
    void slow_server_exceeding_timeout_is_absorbed() throws Exception {
        assertNoMetaUpdated("http://localhost:" + stubPort + "/slow");
    }

    // =========================================================================
    // Description truncated to 300 characters
    // =========================================================================

    @Test
    void description_longer_than_300_chars_is_truncated() throws Exception {
        BroadcastCanvasMessage msg =
                createLinkAndAwaitMetaUpdated("http://localhost:" + stubPort + "/long-description");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) map(msg).get("meta");
        assertThat(((String) meta.get("description"))).hasSize(300);
    }

    // =========================================================================
    // Oversized body (> 100 000 bytes) is capped, OG tags near the start still parse
    // =========================================================================

    @Test
    void oversized_body_is_capped_and_still_parses_leading_tags() throws Exception {
        BroadcastCanvasMessage msg = createLinkAndAwaitMetaUpdated("http://localhost:" + stubPort + "/oversized");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) map(msg).get("meta");
        assertThat(meta.get("title")).isEqualTo("Big page");
        assertThat(meta.get("description")).isEqualTo("Still readable");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BroadcastCanvasMessage createLinkAndAwaitMetaUpdated(final String url) throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> metaUpdated = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(), metaUpdatedHandler(metaUpdated));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("type", "LINK", "content", url)));

        return metaUpdated.get(10, TimeUnit.SECONDS);
    }

    private void assertNoMetaUpdated(final String url) throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> metaUpdated = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(), metaUpdatedHandler(metaUpdated));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "card:create", "data", Map.of("type", "LINK", "content", url)));

        try {
            BroadcastCanvasMessage unexpected = metaUpdated.get(8, TimeUnit.SECONDS);
            throw new AssertionError("Expected no card:meta_updated broadcast, but got: " + unexpected);
        } catch (TimeoutException expected) {
            // Absence confirmed within the wait budget — the desired outcome.
        }
        assertThat(session.isConnected()).isTrue();
    }

    private StompFrameHandler metaUpdatedHandler(final CompletableFuture<BroadcastCanvasMessage> future) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return BroadcastCanvasMessage.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                BroadcastCanvasMessage msg = (BroadcastCanvasMessage) payload;
                if ("card:meta_updated".equals(msg.type())) {
                    future.complete(msg);
                }
            }
        };
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
}
