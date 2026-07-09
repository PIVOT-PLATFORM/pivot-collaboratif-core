package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.collaboratif.whiteboard.canvas.ParticipantMetaStore;
import fr.pivot.collaboratif.whiteboard.canvas.ParticipantsBroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WhiteboardPresenceRegistry} (US08.5.1) — the session-liveness tracker
 * resulting from the collision fix in pivot-collaboratif-core#32. Covers the branch that used
 * to be untestable without a full WebSocket round-trip: whether a disconnecting session was
 * the user's <em>last</em> active session on a board.
 */
@ExtendWith(MockitoExtension.class)
class WhiteboardPresenceRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ParticipantMetaStore participantMetaStore;

    @Mock
    private ParticipantsBroadcastService participantsBroadcastService;

    private WhiteboardPresenceRegistry registry;

    private static final Long TENANT_ID = 100L;
    private static final UUID BOARD_ID = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-1";

    /** Initialises the registry under test with mocked Redis operations. */
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        registry = new WhiteboardPresenceRegistry(redisTemplate, participantMetaStore, participantsBroadcastService);
    }

    /**
     * Given a new JOIN,
     * when registerSession() is called,
     * then the session is added to both the per-user board-sessions SET and the
     * session-reverse-index SET, and no presence broadcast is triggered.
     */
    @Test
    void registerSession_adds_to_both_sets_without_broadcasting() {
        registry.registerSession(TENANT_ID, BOARD_ID, USER_ID, SESSION_ID);

        verify(setOperations).add("board:sessions:" + TENANT_ID + ":" + BOARD_ID + ":" + USER_ID, SESSION_ID);
        verify(setOperations).add(eq("ws:session:" + SESSION_ID), anyString());
        verify(participantsBroadcastService, never()).broadcast(any(), any());
    }

    /**
     * Given an explicit LEAVE,
     * when unregisterSession() is called,
     * then the session is removed from both sets and no presence side-effect is triggered here
     * (presence clearing/broadcast for an explicit LEAVE is CanvasActionService's responsibility).
     */
    @Test
    void unregisterSession_removes_from_both_sets_without_touching_presence() {
        registry.unregisterSession(TENANT_ID, BOARD_ID, USER_ID, SESSION_ID);

        verify(setOperations).remove("board:sessions:" + TENANT_ID + ":" + BOARD_ID + ":" + USER_ID, SESSION_ID);
        verify(setOperations).remove(eq("ws:session:" + SESSION_ID), anyString());
        verify(participantMetaStore, never()).remove(any(), any(), any());
        verify(participantsBroadcastService, never()).broadcast(any(), any());
    }

    /**
     * Given a disconnecting session that was the user's only/last active session on the board,
     * when handleDisconnect() is called,
     * then the participant is removed from {@link ParticipantMetaStore} and a presence
     * broadcast is triggered.
     */
    @Test
    void handleDisconnect_clears_presence_when_it_was_the_last_session() {
        String entry = TENANT_ID + ":" + BOARD_ID + ":" + USER_ID;
        when(setOperations.members("ws:session:" + SESSION_ID)).thenReturn(Set.of(entry));
        when(setOperations.size("board:sessions:" + TENANT_ID + ":" + BOARD_ID + ":" + USER_ID)).thenReturn(0L);

        registry.handleDisconnect(SESSION_ID);

        verify(participantMetaStore).remove(TENANT_ID, BOARD_ID, USER_ID);
        verify(participantsBroadcastService).broadcast(TENANT_ID, BOARD_ID);
        verify(redisTemplate).delete("ws:session:" + SESSION_ID);
    }

    /**
     * Given a disconnecting session while the same user still has another active session on
     * the same board (multi-tab),
     * when handleDisconnect() is called,
     * then presence is left untouched — no metadata removal, no broadcast.
     */
    @Test
    void handleDisconnect_keeps_presence_when_another_session_remains() {
        String entry = TENANT_ID + ":" + BOARD_ID + ":" + USER_ID;
        when(setOperations.members("ws:session:" + SESSION_ID)).thenReturn(Set.of(entry));
        when(setOperations.size("board:sessions:" + TENANT_ID + ":" + BOARD_ID + ":" + USER_ID)).thenReturn(1L);

        registry.handleDisconnect(SESSION_ID);

        verify(participantMetaStore, never()).remove(any(), any(), any());
        verify(participantsBroadcastService, never()).broadcast(any(), any());
    }

    /**
     * Given a session with no registered board rooms (e.g. connected but never JOINed anything),
     * when handleDisconnect() is called,
     * then nothing fails and no presence side-effect occurs.
     */
    @Test
    void handleDisconnect_with_no_entries_does_nothing() {
        when(setOperations.members("ws:session:" + SESSION_ID)).thenReturn(null);

        registry.handleDisconnect(SESSION_ID);

        verify(participantMetaStore, never()).remove(any(), any(), any());
        verify(participantsBroadcastService, never()).broadcast(any(), any());
        verify(redisTemplate).delete("ws:session:" + SESSION_ID);
    }

    /**
     * Given a malformed composite entry in the session's reverse index,
     * when handleDisconnect() is called,
     * then the malformed entry is skipped without throwing.
     */
    @Test
    void handleDisconnect_skips_malformed_entry() {
        when(setOperations.members("ws:session:" + SESSION_ID)).thenReturn(Set.of("not-a-valid-composite-key"));

        registry.handleDisconnect(SESSION_ID);

        verify(participantMetaStore, never()).remove(any(), any(), any());
        verify(participantsBroadcastService, never()).broadcast(any(), any());
    }
}
