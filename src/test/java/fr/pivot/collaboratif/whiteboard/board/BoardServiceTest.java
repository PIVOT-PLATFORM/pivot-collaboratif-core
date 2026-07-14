package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.BoardNotInTrashException;
import fr.pivot.collaboratif.exception.InvalidActivityException;
import fr.pivot.collaboratif.exception.TemplateNotFoundException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardPageResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.PatchBoardRequest;
import fr.pivot.collaboratif.whiteboard.board.dto.SaveAsTemplateRequest;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import fr.pivot.collaboratif.whiteboard.canvas.WhiteboardBroadcastService;
import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplate;
import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BoardService} covering all business branches, including the
 * whiteboard "visible parity" additions (US08.1.6 favorites, US08.1.7 trash/soft-delete,
 * US08.1.8 search, US08.2.4 settings/reset/save-as-template).
 *
 * <p>All external dependencies (repositories, module check, template service, broadcaster)
 * are mocked via Mockito. No Spring context is loaded — tests are fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private BoardMemberRepository boardMemberRepository;

    @Mock
    private BoardFavoriteRepository boardFavoriteRepository;

    @Mock
    private CanvasEventRepository canvasEventRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private WhiteboardModuleCheck moduleCheck;

    @Mock
    private WhiteboardTemplateService templateService;

    @Mock
    private WhiteboardBroadcastService broadcastService;

    private BoardService boardService;

    private static final Long USER_A = 1L;
    private static final Long TENANT_A = 100L;

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        boardService = new BoardService(
                boardRepository, boardMemberRepository, boardFavoriteRepository,
                canvasEventRepository, cardRepository, moduleCheck, templateService,
                broadcastService, new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // create()
    // -------------------------------------------------------------------------

    @Test
    void create_whenModuleEnabled_returnsBoardResponseWithOwnerRole() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        Board savedBoard = boardWithOwner(UUID.randomUUID(), "My Board", USER_A, TENANT_A);
        when(boardRepository.save(any(Board.class))).thenReturn(savedBoard);
        when(boardMemberRepository.save(any(BoardMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BoardResponse response = boardService.create("My Board", USER_A, TENANT_A);

        assertThat(response.title()).isEqualTo("My Board");
        assertThat(response.role()).isEqualTo("OWNER");
        assertThat(response.tenantId()).isEqualTo(TENANT_A);
        assertThat(response.favorite()).isFalse();
    }

    @Test
    void create_whenModuleDisabled_throwsWhiteboardModuleDisabledException() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> boardService.create("Title", USER_A, TENANT_A))
                .isInstanceOf(WhiteboardModuleDisabledException.class);
    }

    @Test
    void create_withoutTemplateId_neverResolvesTemplate() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        Board savedBoard = boardWithOwner(UUID.randomUUID(), "My Board", USER_A, TENANT_A);
        when(boardRepository.save(any(Board.class))).thenReturn(savedBoard);
        when(boardMemberRepository.save(any(BoardMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boardService.create("My Board", USER_A, TENANT_A);

        verify(templateService, never()).resolveGlobalTemplate(anyString());
        verify(templateService, never()).initializeBoard(any(), any(), any(), any());
    }

    @Test
    void create_withValidTemplateId_initializesBoardFromTemplate() {
        UUID templateUuid = UUID.randomUUID();
        WhiteboardTemplate template = mock(WhiteboardTemplate.class);
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(templateService.resolveGlobalTemplate(templateUuid.toString())).thenReturn(template);
        Board savedBoard = boardWithOwner(UUID.randomUUID(), "My Board", USER_A, TENANT_A);
        when(boardRepository.save(any(Board.class))).thenReturn(savedBoard);
        when(boardMemberRepository.save(any(BoardMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BoardResponse response =
                boardService.create("My Board", USER_A, TENANT_A, templateUuid.toString());

        assertThat(response.title()).isEqualTo("My Board");
        verify(templateService).initializeBoard(template, savedBoard.getId(), TENANT_A, USER_A);
    }

    @Test
    void create_withUnknownTemplateId_propagatesTemplateNotFoundAndDoesNotPersistBoard() {
        UUID templateUuid = UUID.randomUUID();
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(templateService.resolveGlobalTemplate(templateUuid.toString()))
                .thenThrow(new TemplateNotFoundException(templateUuid));

        assertThatThrownBy(() ->
                boardService.create("My Board", USER_A, TENANT_A, templateUuid.toString()))
                .isInstanceOf(TemplateNotFoundException.class);

        verify(boardRepository, never()).save(any(Board.class));
    }

    // -------------------------------------------------------------------------
    // findAccessible() — list + search (US08.1.8)
    // -------------------------------------------------------------------------

    @Test
    void findAccessible_returnsOwnedBoards() {
        Board board = boardWithOwner(UUID.randomUUID(), "Board 1", USER_A, TENANT_A);
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(board)));
        when(boardFavoriteRepository.findFavoritedBoardIds(eq(USER_A), any())).thenReturn(List.of());

        BoardPageResponse page = boardService.findAccessible(USER_A, TENANT_A, null, 0, 20);

        assertThat(page.boards()).hasSize(1);
        assertThat(page.boards().get(0).role()).isEqualTo("OWNER");
        assertThat(page.boards().get(0).favorite()).isFalse();
        assertThat(page.currentPage()).isEqualTo(0);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void findAccessible_whenSizeIsZero_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> boardService.findAccessible(USER_A, TENANT_A, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findAccessible_cappsSizeAtMax50() {
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable pageable = inv.getArgument(3);
                    assertThat(pageable.getPageSize()).isEqualTo(50);
                    return new PageImpl<>(List.of());
                });

        boardService.findAccessible(USER_A, TENANT_A, null, 0, 100);
    }

    @Test
    void ac08_1_8_01_findAccessible_normalizesSearchTermLowercaseAndAccentStripped() {
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), eq("retro"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        boardService.findAccessible(USER_A, TENANT_A, "  RÉTRO  ", 0, 20);

        verify(boardRepository).findAccessibleByUser(eq(USER_A), eq(TENANT_A), eq("retro"), any(Pageable.class));
    }

    @Test
    void ac08_1_8_02_findAccessible_blankSearchIsPassedAsNull() {
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        boardService.findAccessible(USER_A, TENANT_A, "   ", 0, 20);

        verify(boardRepository).findAccessibleByUser(eq(USER_A), eq(TENANT_A), isNull(), any(Pageable.class));
    }

    @Test
    void ac08_1_6_03_findAccessible_marksFavoritedBoards() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Fav", USER_A, TENANT_A);
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(board)));
        when(boardFavoriteRepository.findFavoritedBoardIds(eq(USER_A), any())).thenReturn(List.of(boardId));

        BoardPageResponse page = boardService.findAccessible(USER_A, TENANT_A, null, 0, 20);

        assertThat(page.boards().get(0).favorite()).isTrue();
    }

    // -------------------------------------------------------------------------
    // findById()
    // -------------------------------------------------------------------------

    @Test
    void findById_whenOwner_returnsBoardWithOwnerRole() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "My Board", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(false);

        BoardResponse response = boardService.findById(boardId, USER_A, TENANT_A);

        assertThat(response.id()).isEqualTo(boardId);
        assertThat(response.role()).isEqualTo("OWNER");
    }

    @Test
    void findById_whenNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.findById(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void findById_whenMemberNotOwner_returnsBoardWithMemberRole() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 10L;
        Long editorId = 11L;
        Board board = boardWithOwner(boardId, "Shared Board", ownerId, TENANT_A);
        BoardMember member = new BoardMember(
                new BoardMemberId(boardId, editorId), BoardRole.EDITOR, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .thenReturn(Optional.of(member));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, editorId)).thenReturn(false);

        BoardResponse response = boardService.findById(boardId, editorId, TENANT_A);

        assertThat(response.role()).isEqualTo("EDITOR");
    }

    // -------------------------------------------------------------------------
    // findById() — cards + fieldValues + shareCount (US08.1.9)
    // -------------------------------------------------------------------------

    @Test
    void ac08_1_9_01_findById_includesCardsWithFieldValues() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "My Board", USER_A, TENANT_A);
        Card card = new Card(boardId, TENANT_A, CardType.TEXT, "Hello", 1, 2, Instant.now());
        card.setMeta("{\"title\":\"OpenGraph title\"}");
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(false);
        when(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, TENANT_A))
                .thenReturn(List.of(card));

        BoardResponse response = boardService.findById(boardId, USER_A, TENANT_A);

        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().get(0).content()).isEqualTo("Hello");
        assertThat(response.cards().get(0).fieldValues()).containsEntry("title", "OpenGraph title");
    }

    @Test
    void ac08_1_9_02_findById_cardWithoutMeta_hasNullFieldValues() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "My Board", USER_A, TENANT_A);
        Card card = new Card(boardId, TENANT_A, CardType.TEXT, "No meta", 0, 0, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(false);
        when(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, TENANT_A))
                .thenReturn(List.of(card));

        BoardResponse response = boardService.findById(boardId, USER_A, TENANT_A);

        assertThat(response.cards().get(0).fieldValues()).isNull();
    }

    @Test
    void ac08_1_9_03_findById_noCards_returnsEmptyCardsList() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Empty Board", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(false);
        when(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, TENANT_A))
                .thenReturn(List.of());

        BoardResponse response = boardService.findById(boardId, USER_A, TENANT_A);

        assertThat(response.cards()).isEmpty();
    }

    @Test
    void ac08_1_9_04_findById_reflectsShareCountExcludingOwner() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Shared", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(false);
        when(boardMemberRepository.countByIdBoardIdAndRoleNot(boardId, BoardRole.OWNER)).thenReturn(3L);

        BoardResponse response = boardService.findById(boardId, USER_A, TENANT_A);

        assertThat(response.shareCount()).isEqualTo(3);
    }

    @Test
    void ac08_1_9_05_findAccessible_populatesShareCountPerBoard() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Board", USER_A, TENANT_A);
        when(boardRepository.findAccessibleByUser(eq(USER_A), eq(TENANT_A), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(board)));
        when(boardFavoriteRepository.findFavoritedBoardIds(eq(USER_A), any())).thenReturn(List.of());
        when(boardMemberRepository.countByIdBoardIdAndRoleNot(boardId, BoardRole.OWNER)).thenReturn(2L);

        BoardPageResponse page = boardService.findAccessible(USER_A, TENANT_A, null, 0, 20);

        assertThat(page.boards().get(0).shareCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // create() — extended settings contract (US08.1.9)
    // -------------------------------------------------------------------------

    @Test
    void ac08_1_9_06_create_withSettingsFields_persistsThemOnBoard() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        Board savedBoard = boardWithOwner(UUID.randomUUID(), "My Board", USER_A, TENANT_A);
        when(boardRepository.save(any(Board.class))).thenAnswer(inv -> {
            Board passed = inv.getArgument(0);
            assertThat(passed.getMaxParticipants()).isEqualTo(8);
            assertThat(passed.getEnabledActivities()).containsExactly("VOTE");
            assertThat(passed.getCoverImage()).isEqualTo("cover.png");
            return savedBoard;
        });
        when(boardMemberRepository.save(any(BoardMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        boardService.create(
                "My Board", USER_A, TENANT_A, null, 8, List.of("VOTE"), "cover.png");

        verify(boardRepository).save(any(Board.class));
    }

    @Test
    void ac08_1_9_07_create_withUnknownActivity_throwsInvalidActivityAndPersistsNothing() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);

        assertThatThrownBy(() -> boardService.create(
                "My Board", USER_A, TENANT_A, null, null, List.of("NOT_A_REAL_ACTIVITY"), null))
                .isInstanceOf(InvalidActivityException.class);

        verify(boardRepository, never()).save(any());
        verify(boardMemberRepository, never()).save(any());
    }

    @Test
    void ac08_1_9_08_create_withoutSettingsFields_boardHasDefaults() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        Board savedBoard = boardWithOwner(UUID.randomUUID(), "My Board", USER_A, TENANT_A);
        when(boardRepository.save(any(Board.class))).thenReturn(savedBoard);
        when(boardMemberRepository.save(any(BoardMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BoardResponse response = boardService.create(
                "My Board", USER_A, TENANT_A, null, null, null, null);

        assertThat(response.maxParticipants()).isNull();
        assertThat(response.enabledActivities()).isEmpty();
        assertThat(response.coverImage()).isNull();
        assertThat(response.shareCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // patch() — rename + settings (US08.1.4 / US08.2.4)
    // -------------------------------------------------------------------------

    @Test
    void patch_whenOwner_updatesTitle() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Old Title", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardRepository.save(board)).thenReturn(board);

        BoardResponse response = boardService.patch(
                boardId, new PatchBoardRequest("New Title", null, null, null, null), USER_A, TENANT_A);

        assertThat(response.title()).isEqualTo("New Title");
        verify(boardRepository).save(board);
    }

    @Test
    void ac08_2_4_02_patch_whenOwner_updatesDescriptionAndSettings() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "T", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardRepository.save(board)).thenReturn(board);

        BoardResponse response = boardService.patch(
                boardId,
                new PatchBoardRequest(null, "A description", "cover.png", 10, List.of("VOTE")),
                USER_A, TENANT_A);

        assertThat(response.description()).isEqualTo("A description");
        assertThat(response.coverImage()).isEqualTo("cover.png");
        assertThat(response.maxParticipants()).isEqualTo(10);
        assertThat(response.enabledActivities()).containsExactly("VOTE");
    }

    @Test
    void ac08_2_4_03_patch_withUnknownActivity_throwsInvalidActivityException() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "T", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));

        assertThatThrownBy(() -> boardService.patch(
                boardId,
                new PatchBoardRequest(null, null, null, null, List.of("NOT_A_REAL_ACTIVITY")),
                USER_A, TENANT_A))
                .isInstanceOf(InvalidActivityException.class);
        verify(boardRepository, never()).save(any());
    }

    @Test
    void patch_whenEditor_throwsBoardAccessDeniedException() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 30L;
        Long editorId = 31L;
        Board board = boardWithOwner(boardId, "Title", ownerId, TENANT_A);
        BoardMember member = new BoardMember(
                new BoardMemberId(boardId, editorId), BoardRole.EDITOR, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> boardService.patch(
                boardId, new PatchBoardRequest("New Title", null, null, null, null), editorId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    void patch_whenBoardNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.patch(
                boardId, new PatchBoardRequest("New Title", null, null, null, null), USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // softDelete() (US08.1.7)
    // -------------------------------------------------------------------------

    @Test
    void ac08_1_7_01_softDelete_whenOwner_setsDeletedAtNotHardDelete() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "To Trash", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));

        boardService.softDelete(boardId, USER_A, TENANT_A);

        assertThat(board.getDeletedAt()).isNotNull();
        verify(boardRepository).save(board);
        verify(boardRepository, never()).delete(any());
    }

    @Test
    void softDelete_whenViewer_throwsBoardAccessDeniedException() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 50L;
        Long viewerId = 51L;
        Board board = boardWithOwner(boardId, "Title", ownerId, TENANT_A);
        BoardMember member = new BoardMember(
                new BoardMemberId(boardId, viewerId), BoardRole.VIEWER, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, viewerId))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> boardService.softDelete(boardId, viewerId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    void softDelete_whenBoardNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.softDelete(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // restore() (US08.1.7)
    // -------------------------------------------------------------------------

    @Test
    void ac08_1_7_03_restore_whenOwnerAndTrashed_clearsDeletedAt() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Trashed", USER_A, TENANT_A);
        board.setDeletedAt(Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        boardService.restore(boardId, USER_A, TENANT_A);

        assertThat(board.getDeletedAt()).isNull();
        verify(boardRepository).save(board);
    }

    @Test
    void ac08_1_7_07_restore_whenNotInTrash_throwsConflict() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Not trashed", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        assertThatThrownBy(() -> boardService.restore(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotInTrashException.class);
    }

    @Test
    void ac08_1_7_08_restore_whenNonOwner_throwsAccessDenied() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 60L;
        Long editorId = 61L;
        Board board = boardWithOwner(boardId, "Trashed", ownerId, TENANT_A);
        board.setDeletedAt(Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        assertThatThrownBy(() -> boardService.restore(boardId, editorId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    void restore_whenBoardNotFound_throwsBoardNotFoundException() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.restore(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // permanentDelete() (US08.1.7)
    // -------------------------------------------------------------------------

    @Test
    void permanentDelete_whenOwnerAndTrashed_hardDeletes() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Trashed", USER_A, TENANT_A);
        board.setDeletedAt(Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        boardService.permanentDelete(boardId, USER_A, TENANT_A);

        verify(boardRepository).delete(board);
    }

    @Test
    void permanentDelete_whenNotInTrash_throwsConflict() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Not trashed", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        assertThatThrownBy(() -> boardService.permanentDelete(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotInTrashException.class);
        verify(boardRepository, never()).delete(any());
    }

    @Test
    void permanentDelete_whenNonOwner_throwsAccessDenied() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Trashed", 70L, TENANT_A);
        board.setDeletedAt(Instant.now());
        when(boardRepository.findByIdAndTenantId(boardId, TENANT_A)).thenReturn(Optional.of(board));

        assertThatThrownBy(() -> boardService.permanentDelete(boardId, 71L, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    // -------------------------------------------------------------------------
    // findTrashed() (US08.1.7)
    // -------------------------------------------------------------------------

    @Test
    void ac08_1_7_02_findTrashed_returnsOwnedTrashedBoardsWithDeletedAt() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "Trashed", USER_A, TENANT_A);
        Instant deletedAt = Instant.now();
        board.setDeletedAt(deletedAt);
        when(boardRepository.findByOwnerIdAndTenantIdAndDeletedAtIsNotNull(
                eq(USER_A), eq(TENANT_A), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(board)));

        BoardPageResponse page = boardService.findTrashed(USER_A, TENANT_A, 0, 20);

        assertThat(page.boards()).hasSize(1);
        assertThat(page.boards().get(0).deletedAt()).isEqualTo(deletedAt);
        assertThat(page.boards().get(0).role()).isEqualTo("OWNER");
    }

    @Test
    void findTrashed_whenSizeZero_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> boardService.findTrashed(USER_A, TENANT_A, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // favorites (US08.1.6)
    // -------------------------------------------------------------------------

    @Test
    void ac08_1_6_01_addFavorite_whenMember_persistsFavorite() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "B", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(false);

        boardService.addFavorite(boardId, USER_A, TENANT_A);

        verify(boardFavoriteRepository).save(any(BoardFavorite.class));
    }

    @Test
    void ac08_1_6_02_addFavorite_whenAlreadyFavorited_isIdempotentNoOp() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "B", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, USER_A)).thenReturn(true);

        boardService.addFavorite(boardId, USER_A, TENANT_A);

        verify(boardFavoriteRepository, never()).save(any());
    }

    @Test
    void ac08_1_6_07_addFavorite_whenBoardInaccessible_throws404() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.addFavorite(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
        verify(boardFavoriteRepository, never()).save(any());
    }

    @Test
    void ac08_1_6_08_addFavorite_whenNotMember_throws404() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "B", 80L, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, 81L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.addFavorite(boardId, 81L, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void ac08_1_6_09_removeFavorite_deletesOnlyCallerMarker() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "B", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));

        boardService.removeFavorite(boardId, USER_A, TENANT_A);

        verify(boardFavoriteRepository).deleteByIdBoardIdAndIdUserId(boardId, USER_A);
    }

    @Test
    void removeFavorite_whenBoardInaccessible_throws404() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.removeFavorite(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // reset() (US08.2.4)
    // -------------------------------------------------------------------------

    @Test
    void ac08_2_4_06_reset_whenOwner_deletesEventsAndBroadcasts() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "B", USER_A, TENANT_A);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));

        boardService.reset(boardId, USER_A, TENANT_A);

        verify(canvasEventRepository).deleteAllByBoardIdAndTenantId(boardId, TENANT_A);
        verify(broadcastService).broadcastReset(boardId, USER_A);
    }

    @Test
    void ac08_2_4_07_reset_whenEditor_isAllowed() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 90L;
        Long editorId = 91L;
        Board board = boardWithOwner(boardId, "B", ownerId, TENANT_A);
        BoardMember member = new BoardMember(
                new BoardMemberId(boardId, editorId), BoardRole.EDITOR, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .thenReturn(Optional.of(member));

        boardService.reset(boardId, editorId, TENANT_A);

        verify(canvasEventRepository).deleteAllByBoardIdAndTenantId(boardId, TENANT_A);
        verify(broadcastService).broadcastReset(boardId, editorId);
    }

    @Test
    void ac08_2_4_08_reset_whenViewer_throwsAccessDenied() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 92L;
        Long viewerId = 93L;
        Board board = boardWithOwner(boardId, "B", ownerId, TENANT_A);
        BoardMember member = new BoardMember(
                new BoardMemberId(boardId, viewerId), BoardRole.VIEWER, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, viewerId))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> boardService.reset(boardId, viewerId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
        verify(canvasEventRepository, never()).deleteAllByBoardIdAndTenantId(any(), any());
        verify(broadcastService, never()).broadcastReset(any(), any());
    }

    @Test
    void reset_whenBoardInaccessible_throws404() {
        UUID boardId = UUID.randomUUID();
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.reset(boardId, USER_A, TENANT_A))
                .isInstanceOf(BoardNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // saveAsTemplate() (US08.2.4)
    // -------------------------------------------------------------------------

    @Test
    void ac08_2_4_04_saveAsTemplate_whenOwner_createsTemplate() {
        UUID boardId = UUID.randomUUID();
        Board board = boardWithOwner(boardId, "B", USER_A, TENANT_A);
        WhiteboardTemplate template = new WhiteboardTemplate(
                UUID.randomUUID(), TENANT_A, "My Template", "desc", null);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(templateService.createFromBoard(boardId, TENANT_A, "My Template", "desc"))
                .thenReturn(template);

        var response = boardService.saveAsTemplate(
                boardId, new SaveAsTemplateRequest("My Template", "desc"), USER_A, TENANT_A);

        assertThat(response.name()).isEqualTo("My Template");
        verify(templateService).createFromBoard(boardId, TENANT_A, "My Template", "desc");
    }

    @Test
    void ac08_2_4_09_saveAsTemplate_whenNonOwner_throwsAccessDenied() {
        UUID boardId = UUID.randomUUID();
        Long ownerId = 95L;
        Long editorId = 96L;
        Board board = boardWithOwner(boardId, "B", ownerId, TENANT_A);
        BoardMember member = new BoardMember(
                new BoardMemberId(boardId, editorId), BoardRole.EDITOR, Instant.now());
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, TENANT_A))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, editorId))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> boardService.saveAsTemplate(
                boardId, new SaveAsTemplateRequest("T", null), editorId, TENANT_A))
                .isInstanceOf(BoardAccessDeniedException.class);
        verify(templateService, never()).createFromBoard(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a Board instance with the given id set via reflection, simulating a
     * JPA-persisted entity whose id is assigned by the database.
     */
    private Board boardWithOwner(
            final UUID id, final String title, final Long ownerId, final Long tenantId) {
        Board board = new Board(title, tenantId, ownerId, Instant.now());
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
