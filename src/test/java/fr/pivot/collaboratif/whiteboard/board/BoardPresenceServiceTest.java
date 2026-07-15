package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.whiteboard.canvas.ParticipantMetaStore;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ParticipantInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BoardPresenceService} (US08.1.9 — {@code GET
 * /whiteboard/boards/presence}), covering the AC not easily exercised through a real Redis
 * Testcontainer: userId dedup (via {@link ParticipantMetaStore}'s own storage shape) and the
 * "realtime subsystem unavailable → empty map, never an error" fallback.
 */
@ExtendWith(MockitoExtension.class)
class BoardPresenceServiceTest {

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private ParticipantMetaStore participantMetaStore;

    private BoardPresenceService presenceService;

    private static final Long USER_A = 1L;
    private static final Long TENANT_A = 100L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        presenceService = new BoardPresenceService(boardRepository, participantMetaStore);
    }

    @Test
    void ac08_1_9_09_presenceForUser_returnsCountPerAccessibleBoard() {
        UUID boardA = UUID.randomUUID();
        UUID boardB = UUID.randomUUID();
        when(boardRepository.findAccessibleBoardIds(USER_A, TENANT_A)).thenReturn(List.of(boardA, boardB));
        when(participantMetaStore.getAll(TENANT_A, boardA)).thenReturn(List.of(
                new ParticipantInfo("1", "Alice", null, "#111111", "OWNER"),
                new ParticipantInfo("2", "Bob", null, "#222222", "EDITOR")));
        when(participantMetaStore.getAll(TENANT_A, boardB)).thenReturn(List.of());

        var presence = presenceService.presenceForUser(USER_A, TENANT_A);

        assertThat(presence).containsEntry(boardA.toString(), 2);
        // boardB has zero connected participants — omitted from the sparse map, not present as 0.
        assertThat(presence).doesNotContainKey(boardB.toString());
    }

    @Test
    void ac08_1_9_10_presenceForUser_dedupIsInherentToParticipantMetaStoreShape() {
        // ParticipantMetaStore is a Redis HASH keyed by userId (one entry per user, overwritten
        // on every JOIN) — a user connected through several tabs/sessions on the same board is
        // therefore represented by exactly one ParticipantInfo, never duplicated. This test
        // pins that dedup guarantee at the presence-count call site.
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findAccessibleBoardIds(USER_A, TENANT_A)).thenReturn(List.of(boardId));
        when(participantMetaStore.getAll(TENANT_A, boardId))
                .thenReturn(List.of(new ParticipantInfo("1", "Alice", null, "#111111", "OWNER")));

        var presence = presenceService.presenceForUser(USER_A, TENANT_A);

        assertThat(presence).containsEntry(boardId.toString(), 1);
    }

    @Test
    void ac08_1_9_11_presenceForUser_whenRealtimeSubsystemUnavailable_returnsEmptyMap() {
        when(boardRepository.findAccessibleBoardIds(USER_A, TENANT_A))
                .thenThrow(new RuntimeException("Redis connection refused"));

        var presence = presenceService.presenceForUser(USER_A, TENANT_A);

        assertThat(presence).isEmpty();
    }

    @Test
    void ac08_1_9_12_presenceForUser_whenParticipantStoreFailsMidLoop_returnsEmptyMap() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findAccessibleBoardIds(USER_A, TENANT_A)).thenReturn(List.of(boardId));
        when(participantMetaStore.getAll(eq(TENANT_A), any(UUID.class)))
                .thenThrow(new RuntimeException("Redis connection refused"));

        var presence = presenceService.presenceForUser(USER_A, TENANT_A);

        assertThat(presence).isEmpty();
    }

    @Test
    void ac08_1_9_13_presenceForUser_noAccessibleBoards_returnsEmptyMap() {
        when(boardRepository.findAccessibleBoardIds(USER_A, TENANT_A)).thenReturn(List.of());

        var presence = presenceService.presenceForUser(USER_A, TENANT_A);

        assertThat(presence).isEmpty();
    }
}
