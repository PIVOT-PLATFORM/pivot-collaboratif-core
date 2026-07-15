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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for EN08 (Frames) — the durable {@link Frame} model and its {@code frame:*}
 * STOMP contract, verified end-to-end over a real STOMP client against Testcontainers.
 *
 * <p>Verifies the exact wire shapes the frontend ({@code board.store.ts}) subscribes to:
 * <ol>
 *   <li>{@code frame:create} persists a frame with the server-authoritative defaults and
 *       broadcasts a <strong>flat</strong> {@code frame:created} (all Frame fields at the top
 *       level of {@code data}).</li>
 *   <li>{@code frame:create} applies optional {@code title}/{@code color}/{@code width}/
 *       {@code height} when the payload carries them (duplicate-frame path).</li>
 *   <li>{@code frame:move} updates position and broadcasts the full flat {@code frame:moved}.</li>
 *   <li>{@code frame:resize} updates size and broadcasts the full flat {@code frame:resized}.</li>
 *   <li>{@code frame:update} patches {@code title} (and separately {@code active}) and broadcasts
 *       the full flat {@code frame:updated}.</li>
 *   <li>{@code frame:delete} broadcasts the <strong>bare id string</strong> {@code frame:deleted}
 *       and is an idempotent no-op on an already-deleted id.</li>
 *   <li>{@code frame:layer} broadcasts {@code {id, layer}} {@code frame:layered}.</li>
 *   <li>{@code board:state} on JOIN includes the board's existing frames.</li>
 *   <li>A frame from a different board cannot be mutated via a forged destination boardId
 *       (tenant/board isolation).</li>
 *   <li>A VIEWER cannot mutate a frame (silent refusal).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardFrameIT {

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
    private FrameRepository frameRepository;

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
    // Test 1 — frame:create persists defaults and broadcasts a flat frame
    // =========================================================================

    @Test
    void frame_create_persists_with_defaults_and_broadcasts_flat_frame() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:create", "data", Map.of("posX", 10.0, "posY", 20.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:created");
        Map<String, Object> frame = map(msg);
        assertThat(frame.get("id")).isNotNull();
        assertThat(frame.get("boardId")).isEqualTo(board.getId().toString());
        assertThat(frame.get("title")).isEqualTo("");
        assertThat(((Number) frame.get("posX")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) frame.get("posY")).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) frame.get("width")).doubleValue()).isEqualTo(400.0);
        assertThat(((Number) frame.get("height")).doubleValue()).isEqualTo(300.0);
        assertThat(frame.get("color")).isEqualTo("#94A3B8");
        assertThat(frame.get("active")).isEqualTo(false);
        assertThat(((Number) frame.get("layer")).intValue()).isZero();

        Thread.sleep(200);
        List<Frame> frames = frameRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(board.getId(), tenantId);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getPosX()).isEqualTo(10.0);
    }

    // =========================================================================
    // Test 2 — frame:create honours optional title/color/width/height
    // =========================================================================

    @Test
    void frame_create_applies_optional_fields_when_present() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        Map<String, Object> data = new HashMap<>();
        data.put("posX", 5.0);
        data.put("posY", 6.0);
        data.put("title", "Sprint backlog");
        data.put("color", "#FF0000");
        data.put("width", 800.0);
        data.put("height", 600.0);
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:create", "data", data));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:created");
        Map<String, Object> frame = map(msg);
        assertThat(frame.get("title")).isEqualTo("Sprint backlog");
        assertThat(frame.get("color")).isEqualTo("#FF0000");
        assertThat(((Number) frame.get("width")).doubleValue()).isEqualTo(800.0);
        assertThat(((Number) frame.get("height")).doubleValue()).isEqualTo(600.0);
    }

    // =========================================================================
    // Test 3 — frame:move updates position and broadcasts the full flat frame
    // =========================================================================

    @Test
    void frame_move_updates_position_and_broadcasts_full_frame() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:move",
                        "data", Map.of("id", frame.getId().toString(), "posX", 111.0, "posY", 222.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:moved");
        Map<String, Object> body = map(msg);
        assertThat(body.get("id")).isEqualTo(frame.getId().toString());
        assertThat(((Number) body.get("posX")).doubleValue()).isEqualTo(111.0);
        assertThat(((Number) body.get("posY")).doubleValue()).isEqualTo(222.0);
        // Full flat frame — not just {id, posX, posY}.
        assertThat(body).containsKeys("boardId", "title", "width", "height", "color", "active", "layer");

        Frame reloaded = frameRepository.findById(frame.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isEqualTo(111.0);
        assertThat(reloaded.getPosY()).isEqualTo(222.0);
    }

    // =========================================================================
    // Test 4 — frame:resize updates size and broadcasts the full flat frame
    // =========================================================================

    @Test
    void frame_resize_updates_size_and_broadcasts_full_frame() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:resize",
                        "data", Map.of("id", frame.getId().toString(), "width", 999.0, "height", 555.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:resized");
        Map<String, Object> body = map(msg);
        assertThat(((Number) body.get("width")).doubleValue()).isEqualTo(999.0);
        assertThat(((Number) body.get("height")).doubleValue()).isEqualTo(555.0);
        assertThat(body).containsKeys("id", "boardId", "posX", "posY", "title", "color", "active", "layer");

        Frame reloaded = frameRepository.findById(frame.getId()).orElseThrow();
        assertThat(reloaded.getWidth()).isEqualTo(999.0);
        assertThat(reloaded.getHeight()).isEqualTo(555.0);
    }

    // =========================================================================
    // Test 5 — frame:update patches title, broadcasts the full flat frame
    // =========================================================================

    @Test
    void frame_update_title_broadcasts_full_frame() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:update",
                        "data", Map.of("id", frame.getId().toString(), "title", "Iteration 3")));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:updated");
        Map<String, Object> body = map(msg);
        assertThat(body.get("id")).isEqualTo(frame.getId().toString());
        assertThat(body.get("title")).isEqualTo("Iteration 3");
        assertThat(body).containsKeys("boardId", "posX", "posY", "width", "height", "color", "active", "layer");

        Frame reloaded = frameRepository.findById(frame.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Iteration 3");
    }

    // =========================================================================
    // Test 5b — frame:update patches active alone (the frontend's toggleFrameActive)
    // =========================================================================

    @Test
    void frame_update_active_broadcasts_full_frame() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:update",
                        "data", Map.of("id", frame.getId().toString(), "active", true)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:updated");
        assertThat(map(msg).get("active")).isEqualTo(true);

        Frame reloaded = frameRepository.findById(frame.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
    }

    // =========================================================================
    // Test 6 — frame:delete broadcasts the bare id string
    // =========================================================================

    @Test
    void frame_delete_broadcasts_bare_id_string() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:delete", "data", Map.of("id", frame.getId().toString())));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:deleted");
        // Bare id string as data, matching this.on<string>('frame:deleted', …) — not an { id } object.
        assertThat(msg.data()).isEqualTo(frame.getId().toString());

        Thread.sleep(200);
        assertThat(frameRepository.findById(frame.getId())).isEmpty();
    }

    // =========================================================================
    // Test 6b — frame:delete on an already-deleted id is an idempotent no-op
    // =========================================================================

    @Test
    void frame_delete_already_deleted_is_idempotent_noop() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);
        UUID frameId = frame.getId();
        frameRepository.deleteById(frameId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:delete", "data", Map.of("id", frameId.toString())));
        // A subsequent valid create must still succeed — proves the session survived the no-op.
        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:create", "data", Map.of("posX", 1.0, "posY", 1.0)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:created");
        assertThat(session.isConnected()).isTrue();
    }

    // =========================================================================
    // Test 7 — frame:layer broadcasts {id, layer}
    // =========================================================================

    @Test
    void frame_layer_broadcasts_id_and_layer() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        CompletableFuture<BroadcastCanvasMessage> future = new CompletableFuture<>();
        session.subscribe("/topic/whiteboard/" + board.getId(),
                framHandler(BroadcastCanvasMessage.class, future));

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:layer",
                        "data", Map.of("id", frame.getId().toString(), "layer", 7)));

        BroadcastCanvasMessage msg = future.get(5, TimeUnit.SECONDS);
        assertThat(msg.type()).isEqualTo("frame:layered");
        Map<String, Object> body = map(msg);
        assertThat(body.get("id")).isEqualTo(frame.getId().toString());
        assertThat(((Number) body.get("layer")).intValue()).isEqualTo(7);

        Frame reloaded = frameRepository.findById(frame.getId()).orElseThrow();
        assertThat(reloaded.getLayer()).isEqualTo(7);
    }

    // =========================================================================
    // Test 8 — JOIN board:state includes the board's existing frames
    // =========================================================================

    @Test
    void join_board_state_includes_existing_frames() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        seedFrame(board.getId(), tenantId);
        seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(token);
        BlockingQueue<BroadcastCanvasMessage> queue = subscribeQueue(session, board.getId());

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "board:join", "data", board.getId().toString()));

        BroadcastCanvasMessage msg = awaitType(queue, "board:state", 8);
        assertThat(map(msg)).containsKeys("cards", "connections", "frames", "fields");
        @SuppressWarnings("unchecked")
        List<Object> frames = (List<Object>) map(msg).get("frames");
        assertThat(frames).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) frames.get(0);
        assertThat(first).containsKeys("id", "boardId", "title", "posX", "posY", "width", "height", "color",
                "active", "layer");
        assertThat(first.get("boardId")).isEqualTo(board.getId().toString());
    }

    // =========================================================================
    // Test 9 — a frame from another board cannot be mutated via a forged destination
    // =========================================================================

    @Test
    void frame_from_another_board_is_not_mutable_via_forged_destination() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        String token = issueToken(ownerId);
        Board boardA = createBoardWithOwner(tenantId, ownerId);
        Board boardB = createBoardWithOwner(tenantId, ownerId);
        Frame frameOnA = seedFrame(boardA.getId(), tenantId);

        StompSession session = connectAs(token);
        session.subscribe("/topic/whiteboard/" + boardB.getId(), noOpHandler());
        Thread.sleep(100);

        session.send("/app/whiteboard/" + boardB.getId() + "/action",
                Map.of("type", "frame:move",
                        "data", Map.of("id", frameOnA.getId().toString(), "posX", 900.0, "posY", 900.0)));

        Thread.sleep(300);
        Frame reloaded = frameRepository.findById(frameOnA.getId()).orElseThrow();
        assertThat(reloaded.getPosX()).isZero();
        assertThat(reloaded.getPosY()).isZero();
    }

    // =========================================================================
    // Test 10 — a VIEWER cannot mutate a frame (silent refusal)
    // =========================================================================

    @Test
    void viewer_cannot_mutate_frame() throws Exception {
        long tenantId = seedTenant();
        long ownerId = seedUser(tenantId);
        long viewerId = seedUser(tenantId);
        String viewerToken = issueToken(viewerId);
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), viewerId), BoardRole.VIEWER, Instant.now()));
        Frame frame = seedFrame(board.getId(), tenantId);

        StompSession session = connectAs(viewerToken);
        session.subscribe("/topic/whiteboard/" + board.getId(), noOpHandler());
        session.subscribe("/user/queue/errors", noOpHandler());
        Thread.sleep(200);

        session.send("/app/whiteboard/" + board.getId() + "/action",
                Map.of("type", "frame:update",
                        "data", Map.of("id", frame.getId().toString(), "title", "hacked")));

        Thread.sleep(300);
        Frame reloaded = frameRepository.findById(frame.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("");
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

    private Frame seedFrame(final UUID boardId, final long tenantId) {
        return frameRepository.save(new Frame(boardId, tenantId, 0, 0, Instant.now()));
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
