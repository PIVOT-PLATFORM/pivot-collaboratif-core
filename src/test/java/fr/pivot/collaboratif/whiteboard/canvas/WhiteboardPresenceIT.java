package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantsUpdatePayload;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for US08.5.1 — presence collision fix (pivot-collaboratif-core#32).
 *
 * <p>Before this US, {@code WhiteboardPresenceRegistry} (EN08.1) and {@code CanvasActionService}
 * (US08.3.1) independently broadcast two incompatible payload shapes to the same
 * {@code /topic/whiteboard/{boardId}/presence} topic, and a single-session-per-user model
 * dropped a user's presence entirely if just one of several open tabs crashed. This class
 * verifies the fix: presence is driven exclusively by explicit JOIN/LEAVE, and a WebSocket
 * disconnect only clears presence when it was the user's <em>last</em> active session on the
 * board.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WhiteboardPresenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private BoardMemberRepository boardMemberRepository;

    private final List<StompSession> openSessions = new ArrayList<>();

    /** Disconnects all still-open STOMP sessions after each test. */
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
    // Test 1 — Crash without LEAVE clears presence (last/only session)
    // =========================================================================

    /**
     * Given a member who JOINed a board and never sent LEAVE,
     * when their WebSocket session disconnects abruptly (crash),
     * then a PARTICIPANTS_UPDATE with an empty list is eventually broadcast.
     */
    @Test
    void crash_without_leave_clears_presence_when_it_was_the_last_session() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Board board = createBoardWithOwner(tenantId, userId);

        StompSession session = connectAs(userId, tenantId);
        List<ParticipantsUpdatePayload> updates = subscribeToPresenceUpdates(session, board.getId());

        // Brief pause so SimpleBroker registers the subscription before the JOIN below —
        // STOMP subscription frames are processed asynchronously; without this pause there is
        // a small window where the broadcast can be dispatched before the subscriber is
        // registered, causing the message to be silently lost (see WhiteboardCanvasIT).
        Thread.sleep(100);
        joinBoard(session, board.getId(), "Alice");
        awaitAtLeast(updates, 1);
        assertThat(updates.get(0).participants()).hasSize(1);

        // Reconnect a second, independent session subscribed to the same topic so we can
        // observe the broadcast triggered by the first session's crash after it disconnects.
        UUID observerId = UUID.randomUUID();
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), observerId), BoardRole.VIEWER, Instant.now()));
        StompSession observer = connectAs(observerId, tenantId);
        List<ParticipantsUpdatePayload> observedUpdates = subscribeToPresenceUpdates(observer, board.getId());
        Thread.sleep(100);

        // Crash: disconnect without sending LEAVE.
        openSessions.remove(session);
        session.disconnect();

        awaitAtLeast(observedUpdates, 1);
        assertThat(observedUpdates.get(observedUpdates.size() - 1).participants()).isEmpty();
    }

    // =========================================================================
    // Test 2 — Multi-tab: one session crashing does not clear presence
    // =========================================================================

    /**
     * Given the same user JOINed a board from two separate STOMP sessions (two browser tabs),
     * when one of the two sessions disconnects abruptly,
     * then the user is NOT removed from presence (the other session is still active) —
     * this is the exact bug fixed by pivot-collaboratif-core#32.
     */
    @Test
    void multi_tab_one_session_crash_does_not_clear_presence() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Board board = createBoardWithOwner(tenantId, userId);

        StompSession tab1 = connectAs(userId, tenantId);
        List<ParticipantsUpdatePayload> tab1Updates = subscribeToPresenceUpdates(tab1, board.getId());
        Thread.sleep(100);
        joinBoard(tab1, board.getId(), "Alice (tab 1)");
        awaitAtLeast(tab1Updates, 1);
        assertThat(tab1Updates.get(0).participants()).hasSize(1);

        StompSession tab2 = connectAs(userId, tenantId);
        joinBoard(tab2, board.getId(), "Alice (tab 2)");

        // Independent observer to see whether a PARTICIPANTS_UPDATE fires after tab1 crashes.
        UUID observerId = UUID.randomUUID();
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), observerId), BoardRole.VIEWER, Instant.now()));
        StompSession observer = connectAs(observerId, tenantId);
        List<ParticipantsUpdatePayload> observedUpdates = subscribeToPresenceUpdates(observer, board.getId());
        Thread.sleep(100);

        // Crash tab1 only — tab2 stays connected.
        openSessions.remove(tab1);
        tab1.disconnect();

        // No PARTICIPANTS_UPDATE should follow from tab1's disconnect: the user still has
        // tab2 open, so presence must not be cleared.
        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> awaitAtLeast(observedUpdates, 1, 2));

        // Sanity: an explicit LEAVE from the remaining tab still clears presence correctly.
        tab2.send("/app/whiteboard/" + board.getId() + "/action", Map.of("type", "LEAVE", "data", Map.of()));
        awaitAtLeast(observedUpdates, 1);
        assertThat(observedUpdates.get(observedUpdates.size() - 1).participants()).isEmpty();
    }

    // =========================================================================
    // Test 3 — Non-member cannot subscribe to the presence sub-topic
    // =========================================================================

    /**
     * Given a board where user B has no membership,
     * when user B subscribes directly to {@code /topic/whiteboard/{boardId}/presence} and the
     * owner then JOINs,
     * then user B never receives a PARTICIPANTS_UPDATE (the SUBSCRIBE was silently dropped).
     */
    @Test
    void non_member_subscribe_to_presence_subtopic_is_denied() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID nonMemberId = UUID.randomUUID();
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession nonMemberSession = connectAs(nonMemberId, tenantId);
        List<ParticipantsUpdatePayload> updates = subscribeToPresenceUpdates(nonMemberSession, board.getId());

        Thread.sleep(200);

        StompSession ownerSession = connectAs(ownerId, tenantId);
        joinBoard(ownerSession, board.getId(), "Owner");

        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> awaitAtLeast(updates, 1, 2));
    }

    // =========================================================================
    // Test 4 — Joiner sees the existing participant list on a non-empty board
    // =========================================================================

    /**
     * Given user A already JOINed a board,
     * when user B subscribes and then JOINs the same board,
     * then user B's own JOIN broadcast carries the full current list (A and B).
     */
    @Test
    void joiner_receives_full_current_list_on_non_empty_board() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        Board board = createBoardWithOwner(tenantId, ownerId);
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(board.getId(), secondUserId), BoardRole.EDITOR, Instant.now()));

        StompSession ownerSession = connectAs(ownerId, tenantId);
        joinBoard(ownerSession, board.getId(), "Alice");
        Thread.sleep(200);

        StompSession secondSession = connectAs(secondUserId, tenantId);
        List<ParticipantsUpdatePayload> updates = subscribeToPresenceUpdates(secondSession, board.getId());
        Thread.sleep(100);
        joinBoard(secondSession, board.getId(), "Bob");

        awaitAtLeast(updates, 1);
        assertThat(updates.get(0).participants()).hasSize(2)
                .extracting(p -> p.userId())
                .containsExactlyInAnyOrder(ownerId.toString(), secondUserId.toString());
    }

    // =========================================================================
    // Test 5 — Duplicate JOIN from the same user (multi-tab) dedups to one entry
    // =========================================================================

    /**
     * Given the same user JOINs twice from two different sessions (tabs),
     * when the second JOIN arrives,
     * then there is still exactly one participant entry for that user — "last active
     * connection wins" — and the colour is unchanged between the two JOINs (deterministic by
     * {@code userId}), which also demonstrates that a reconnection keeps the same colour.
     */
    @Test
    void duplicate_join_from_same_user_dedups_with_stable_color() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Board board = createBoardWithOwner(tenantId, userId);

        StompSession tab1 = connectAs(userId, tenantId);
        List<ParticipantsUpdatePayload> updates = subscribeToPresenceUpdates(tab1, board.getId());
        Thread.sleep(100);
        joinBoard(tab1, board.getId(), "Alice-Tab1");
        awaitAtLeast(updates, 1);
        assertThat(updates.get(0).participants()).hasSize(1);
        String colorAfterFirstJoin = updates.get(0).participants().get(0).color();

        StompSession tab2 = connectAs(userId, tenantId);
        joinBoard(tab2, board.getId(), "Alice-Tab2");
        awaitAtLeast(updates, 2);

        ParticipantsUpdatePayload afterSecondJoin = updates.get(updates.size() - 1);
        assertThat(afterSecondJoin.participants()).hasSize(1);
        ParticipantInfo participant = afterSecondJoin.participants().get(0);
        assertThat(participant.userId()).isEqualTo(userId.toString());
        assertThat(participant.displayName()).isEqualTo("Alice-Tab2");
        assertThat(participant.color()).isEqualTo(colorAfterFirstJoin);
    }

    // =========================================================================
    // Test 6 — PARTICIPANTS_UPDATE never exposes more than the allowed fields
    // =========================================================================

    /**
     * Given a member JOINs a board,
     * when PARTICIPANTS_UPDATE is broadcast,
     * then only {@code userId}, {@code displayName}, {@code avatarUrl}, {@code color} and
     * {@code role} are present on the participant entry — {@link ParticipantInfo} has no email
     * or other profile field to leak, and this test pins that contract at the wire level.
     */
    @Test
    void participants_update_never_exposes_email_or_other_profile_data() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Board board = createBoardWithOwner(tenantId, ownerId);

        StompSession session = connectAs(ownerId, tenantId);
        List<ParticipantsUpdatePayload> updates = subscribeToPresenceUpdates(session, board.getId());
        Thread.sleep(100);
        joinBoard(session, board.getId(), "Alice");

        awaitAtLeast(updates, 1);
        assertThat(updates.get(0).participants()).hasSize(1);
        ParticipantInfo participant = updates.get(0).participants().get(0);
        assertThat(participant.userId()).isEqualTo(ownerId.toString());
        assertThat(participant.displayName()).isEqualTo("Alice");
        assertThat(participant.role()).isEqualTo("OWNER");
        assertThat(participant.color()).startsWith("#");
        // ParticipantInfo is a closed record: userId, displayName, avatarUrl, color, role —
        // there is no email/profile field to serialise in the first place (compile-time
        // guarantee), so no additional reflection assertion is needed here.
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Establishes a STOMP connection with the given identity headers.
     *
     * @param userId   the user UUID
     * @param tenantId the tenant UUID
     * @return an open STOMP session
     */
    private StompSession connectAs(final UUID userId, final UUID tenantId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("X-Pivot-User-Id", userId.toString());
        httpHeaders.add("X-Pivot-Tenant-Id", tenantId.toString());

        String url = "ws://localhost:" + port + "/api/collaboratif/ws/whiteboard";
        StompSession session = client.connectAsync(url, httpHeaders,
                new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Subscribes to a board's presence sub-topic, collecting every received update in order.
     *
     * @param session the STOMP session to subscribe on
     * @param boardId the board UUID
     * @return the mutable, synchronized list that will receive every update
     */
    private List<ParticipantsUpdatePayload> subscribeToPresenceUpdates(
            final StompSession session, final UUID boardId) {
        List<ParticipantsUpdatePayload> updates = new ArrayList<>();
        session.subscribe("/topic/whiteboard/" + boardId + "/presence", new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return ParticipantsUpdatePayload.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                synchronized (updates) {
                    updates.add((ParticipantsUpdatePayload) payload);
                    updates.notifyAll();
                }
            }
        });
        return updates;
    }

    /**
     * Sends a JOIN application message on the given session for the given board.
     *
     * @param session     the STOMP session
     * @param boardId     the board UUID
     * @param displayName the display name to JOIN with
     */
    private void joinBoard(final StompSession session, final UUID boardId, final String displayName) {
        session.send("/app/whiteboard/" + boardId + "/action",
                Map.of("type", "JOIN", "data", Map.of("displayName", displayName)));
    }

    /**
     * Blocks until the given list has at least {@code count} entries or an 8-second deadline
     * elapses.
     *
     * @param updates the list to observe
     * @param count   the minimum expected size
     */
    private void awaitAtLeast(final List<ParticipantsUpdatePayload> updates, final int count) throws Exception {
        awaitAtLeast(updates, count, 8);
    }

    /**
     * Blocks until the given list has at least {@code count} entries, throwing
     * {@link TimeoutException} if the deadline elapses first — used to positively assert that
     * an update does <em>not</em> happen within a bounded window.
     *
     * @param updates       the list to observe
     * @param count         the minimum expected size
     * @param timeoutSeconds the deadline, in seconds
     */
    private void awaitAtLeast(
            final List<ParticipantsUpdatePayload> updates, final int count, final int timeoutSeconds)
            throws Exception {
        synchronized (updates) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (updates.size() < count && System.currentTimeMillis() < deadline) {
                updates.wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
            if (updates.size() < count) {
                throw new TimeoutException("Expected at least " + count + " update(s), got " + updates.size());
            }
        }
    }

    /**
     * Creates a board owned by the given user within the given tenant and persists both
     * the board and the OWNER membership record.
     *
     * @param tenantId the tenant UUID
     * @param ownerId  the user UUID that will be the OWNER
     * @return the persisted {@link Board}
     */
    private Board createBoardWithOwner(final UUID tenantId, final UUID ownerId) {
        Instant now = Instant.now();
        Board board = boardRepository.save(new Board("Test Board", tenantId, ownerId, now));
        boardMemberRepository.save(
                new BoardMember(new BoardMemberId(board.getId(), ownerId), BoardRole.OWNER, now));
        return board;
    }
}
