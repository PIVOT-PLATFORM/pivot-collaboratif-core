package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardPageResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BoardService} covering all business branches.
 *
 * <p>All external dependencies (repositories, module check) are mocked via Mockito.
 * No Spring context is loaded — tests are fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private BoardMemberRepository boardMemberRepository;

    @Mock
    private WhiteboardModuleCheck moduleCheck;

    private BoardService boardService;

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        boardService = new BoardService(boardRepository, boardMemberRepository, moduleCheck);
    }

    // -------------------------------------------------------------------------
    // create()
    // -------------------------------------------------------------------------

    /**
     * Given the module is enabled, when create() is called,
     * then it returns a BoardResponse with the correct title, tenantId, and role "owner".
     */
    @Test
    void create_whenModuleEnabled_returnsBoardResponseWithOwnerRole() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        Board savedBoard = boardWithOwner(UUID.randomUUID(), "My Board", USER_A, TENANT_A);
        when(boardRepository.save(any(Board.class))).thenReturn(savedBoard);
        when(boardMemberRepository.save(any(BoardMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BoardResponse response = boardService.create("My Board", USER_A, TENANT_A);

        assertThat(response.title()).isEqualTo("My Board");
        assertThat(response.role()).isEqualTo("owner");
        assertThat(response.tenantId()).isEqualTo(TENANT_A);
    }

    /**
     * Given the module is disabled for the tenant, when create() is called,
     * then it throws {@link WhiteboardModuleDisabledException} (mapped to HTTP 403).
     */
    @Test
    void create_whenModuleDisabled_throwsWhiteboardModuleDisabledException() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> boardService.create("Title", USER_A, TENANT_A))
                .isInstanceOf(WhiteboardModuleDisabledException.class);
    }

    // -------------------------------------------------------------------------
    // findAccessible()
    // -------------------------------------------------------------------------

    /**
     * Given boards owned by the user, when findAccessible() is called,
     * then the returned page contains those boards with role "owner".
     */
    @Test
    void findAccessible_returnsOwnedBoards() {
        Board board = boardWithOwner(UUID.randomUUID(), "Board 1", USER_A, TENANT_A);
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(board)));

        BoardPageResponse page = boardService.findAccessible(USER_A, TENANT_A, 0, 20);

        assertThat(page.boards()).hasSize(1);
        assertThat(page.boards().get(0).role()).isEqualTo("owner");
        assertThat(page.currentPage()).isEqualTo(0);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    /**
     * Given size is zero, when findAccessible() is called,
     * then it throws {@link IllegalArgumentException}.
     */
    @Test
    void findAccessible_whenSizeIsZero_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> boardService.findAccessible(USER_A, TENANT_A, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Given size exceeds 50, when findAccessible() is called,
     * then the effective page size used in the query is capped at 50.
     */
    @Test
    void findAccessible_cappsSizeAtMax50() {
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable pageable = inv.getArgument(2);
                    assertThat(pageable.getPageSize()).isEqualTo(50);
                    return new PageImpl<>(List.of());
                });

        boardService.findAccessible(USER_A, TENANT_A, 0, 100);
    }

    // -------------------------------------------------------------------------
    // findById()
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner, when findById() is called,
     * then it returns a BoardResponse with role "owner".
     */
    @Test
    void findById_whenOwner_returnsBoardWithOwnerRole() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "My Board", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        BoardResponse response = boardService.findById(boardId, USER_A, TENANT_A);

        assertThat(response.id()).isEqualTo(boardId);
        assertThat(response.role()).isEqualTo("owner");
    }

    /**
     * Given no board matches the id+tenantId combination, when findById() is called,
     * then it throws {@link BoardNotFoundException}.
     */
    @Test
    void findById_whenNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.findById(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    /**
     * Given the caller is a board member with EDITOR role, when findById() is called,
     * then it returns a BoardResponse with role "editor".
     */
    @Test
    void findById_whenMemberNotOwner_returnsBoardWithMemberRole() {
        UUID boardId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID editorId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Shared Board", ownerId, TENANT_A);
        BoardMemberId memberId = new BoardMemberId(boardId, editorId);
        BoardMember member = new BoardMember(memberId, BoardRole.EDITOR, Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .thenReturn(Optional.of(member));

        BoardResponse response = boardService.findById(boardId, editorId, TENANT_A);

        assertThat(response.role()).isEqualTo("editor");
    }

    /**
     * Given the caller is neither owner nor member, when findById() is called,
     * then it throws {@link BoardNotFoundException} (to avoid information disclosure).
     */
    @Test
    void findById_whenUserIsNeitherOwnerNorMember_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Private Board", ownerId, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, strangerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.findById(boardId, strangerId, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // rename()
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner and the new title is valid, when rename() is called,
     * then it updates the title and returns the updated response.
     */
    @Test
    void rename_whenOwner_updatesTitle() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Old Title", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));
        when(boardRepository.save(board)).thenReturn(board);

        BoardResponse response = boardService.rename(boardId, "New Title", USER_A, TENANT_A);

        assertThat(response.title()).isEqualTo("New Title");
        verify(boardRepository).save(board);
    }

    /**
     * Given the caller is an EDITOR (not owner), when rename() is called,
     * then it throws {@link BoardAccessDeniedException}.
     */
    @Test
    void rename_whenEditor_throwsBoardAccessDeniedException() {
        UUID boardId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID editorId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Title", ownerId, TENANT_A);
        BoardMemberId memberId = new BoardMemberId(boardId, editorId);
        BoardMember member = new BoardMember(memberId, BoardRole.EDITOR, Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> boardService.rename(boardId, "New Title", editorId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    /**
     * Given the board is not found, when rename() is called,
     * then it throws {@link BoardNotFoundException}.
     */
    @Test
    void rename_whenBoardNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.rename(boardId, "New Title", USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    /**
     * Given the caller is neither owner nor member, when rename() is called,
     * then it throws {@link BoardNotFoundException} (to avoid information disclosure).
     */
    @Test
    void rename_whenNonMember_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Title", ownerId, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, strangerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.rename(boardId, "New Title", strangerId, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // delete()
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner, when delete() is called,
     * then the board is deleted from the repository.
     */
    @Test
    void delete_whenOwner_deletesBoard() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "To Delete", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        boardService.delete(boardId, USER_A, TENANT_A);

        verify(boardRepository).delete(board);
    }

    /**
     * Given the caller is a VIEWER (not owner), when delete() is called,
     * then it throws {@link BoardAccessDeniedException}.
     */
    @Test
    void delete_whenViewer_throwsBoardAccessDeniedException() {
        UUID boardId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Title", ownerId, TENANT_A);
        BoardMemberId memberId = new BoardMemberId(boardId, viewerId);
        BoardMember member = new BoardMember(memberId, BoardRole.VIEWER, Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, viewerId))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> boardService.delete(boardId, viewerId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    /**
     * Given the board is not found, when delete() is called,
     * then it throws {@link BoardNotFoundException}.
     */
    @Test
    void delete_whenBoardNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.delete(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a Board instance with the given id set via reflection,
     * simulating a JPA-persisted entity whose id is assigned by the database.
     *
     * @param id       the UUID to assign to the board
     * @param title    the board title
     * @param ownerId  the owner user UUID
     * @param tenantId the tenant UUID
     * @return a board with the specified id
     */
    private Board boardWithOwner(
            final UUID id, final String title, final UUID ownerId, final UUID tenantId) {
        Instant now = Instant.now();
        Board board = new Board(title, tenantId, ownerId, now);
        try {
            java.lang.reflect.Field field = Board.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(board, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set board id in test", ex);
        }
        return board;
    }
}
