package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardPageResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardResponse;
import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplate;
import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for whiteboard board operations.
 *
 * <p>All read operations are wrapped in a read-only transaction; write operations
 * use a full read-write transaction. The service enforces tenant isolation and role-based
 * access control for every board operation.
 */
@Service
@Transactional(readOnly = true)
public class BoardService {

    /** Default page size for board list queries. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** Maximum allowed page size to prevent unbounded result sets. */
    private static final int MAX_PAGE_SIZE = 50;

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final WhiteboardModuleCheck moduleCheck;
    private final WhiteboardTemplateService templateService;

    /**
     * Creates the service with all required dependencies.
     *
     * @param boardRepository       repository for board persistence
     * @param boardMemberRepository repository for board membership persistence
     * @param moduleCheck           check for whiteboard module activation
     * @param templateService       service resolving templates and initializing a board's
     *                              canvas from one (US08.4.1)
     */
    public BoardService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final WhiteboardModuleCheck moduleCheck,
            final WhiteboardTemplateService templateService) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.moduleCheck = moduleCheck;
        this.templateService = templateService;
    }

    /**
     * Creates a new board and assigns the caller as OWNER.
     *
     * <p>Persists both the board and the initial {@link BoardMember} record in a single
     * transaction. The board's visibility defaults to {@link BoardVisibility#PRIVATE}.
     *
     * @param title    board title (1–100 chars, validated at the controller layer)
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return the created board as a response record
     * @throws WhiteboardModuleDisabledException if the whiteboard module is inactive for the tenant
     */
    @Transactional
    public BoardResponse create(final String title, final Long userId, final Long tenantId) {
        return create(title, userId, tenantId, null);
    }

    /**
     * Creates a new board and assigns the caller as OWNER, optionally initializing its
     * canvas from a template (US08.4.1).
     *
     * <p>Persists the board and the initial {@link BoardMember} record, then — if a
     * {@code templateId} is given — replays the template's elements as {@code DRAW}
     * canvas events on the new board, all within a single transaction: an invalid or
     * unknown {@code templateId} rolls back the board creation itself, leaving no orphan
     * board behind. The board's visibility defaults to {@link BoardVisibility#PRIVATE}.
     *
     * @param title      board title (1–100 chars, validated at the controller layer)
     * @param userId     calling user's {@code public.users.id}
     * @param tenantId   calling tenant's {@code public.tenants.id}
     * @param templateId raw {@code templateId} request parameter, or {@code null}/blank for
     *                   a blank board (US08.1.1 behaviour, unchanged)
     * @return the created board as a response record
     * @throws WhiteboardModuleDisabledException                        if the whiteboard
     *                                                                   module is inactive
     *                                                                   for the tenant
     * @throws fr.pivot.collaboratif.exception.InvalidTemplateIdException if {@code templateId}
     *                                                                    is not a valid UUID
     * @throws fr.pivot.collaboratif.exception.TemplateNotFoundException  if {@code templateId}
     *                                                                    does not resolve to
     *                                                                    an existing global
     *                                                                    template
     */
    @Transactional
    public BoardResponse create(
            final String title, final Long userId, final Long tenantId, final String templateId) {
        if (!moduleCheck.isEnabled(tenantId)) {
            throw new WhiteboardModuleDisabledException(tenantId);
        }
        WhiteboardTemplate template = null;
        if (templateId != null && !templateId.isBlank()) {
            template = templateService.resolveGlobalTemplate(templateId);
        }
        Instant now = Instant.now();
        Board board = boardRepository.save(new Board(title, tenantId, userId, now));
        BoardMemberId memberId = new BoardMemberId(board.getId(), userId);
        boardMemberRepository.save(new BoardMember(memberId, BoardRole.OWNER, now));
        if (template != null) {
            templateService.initializeBoard(template, board.getId(), tenantId, userId);
        }
        return BoardResponse.from(board, BoardRole.OWNER);
    }

    /**
     * Returns a paginated list of boards accessible by the caller (owned or shared).
     *
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @param page     zero-based page number
     * @param size     requested page size (capped at {@link #MAX_PAGE_SIZE})
     * @return paginated board list with metadata
     * @throws IllegalArgumentException if {@code size} is zero or negative
     */
    public BoardPageResponse findAccessible(
            final Long userId,
            final Long tenantId,
            final int page,
            final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive");
        }
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
                page, effectiveSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Board> boardPage = boardRepository.findAccessibleByUser(userId, tenantId, pageable);
        List<BoardResponse> responses = boardPage.getContent().stream()
                .map(b -> toBoardResponse(b, userId))
                .toList();
        return new BoardPageResponse(
                responses,
                boardPage.getTotalElements(),
                boardPage.getTotalPages(),
                boardPage.getNumber(),
                boardPage.hasNext());
    }

    /**
     * Returns a single board if the caller has access to it.
     *
     * <p>Both ownership and membership are checked. A 404 is returned regardless of whether
     * the board simply does not exist or belongs to a different tenant (to avoid information
     * disclosure).
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return board response for the caller
     * @throws BoardNotFoundException if the board does not exist, belongs to another tenant,
     *                                or the caller is not a member or owner
     */
    public BoardResponse findById(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        return BoardResponse.from(board, role);
    }

    /**
     * Renames a board; only the OWNER may rename.
     *
     * @param boardId  the board UUID
     * @param newTitle new title (1–100 chars, validated at the controller layer)
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return updated board response
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     */
    @Transactional
    public BoardResponse rename(
            final UUID boardId,
            final String newTitle,
            final Long userId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        if (role != BoardRole.OWNER) {
            throw new BoardAccessDeniedException(boardId);
        }
        String oldTitle = board.getTitle();
        board.setTitle(newTitle);
        Board saved = boardRepository.save(board);
        logAuditEvent("BoardRenamed", boardId, userId,
                "oldTitle=" + oldTitle + " newTitle=" + newTitle);
        return BoardResponse.from(saved, BoardRole.OWNER);
    }

    /**
     * Permanently deletes a board and all its data (cascaded via foreign key); only the OWNER
     * may delete.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     */
    @Transactional
    public void delete(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        if (role != BoardRole.OWNER) {
            throw new BoardAccessDeniedException(boardId);
        }
        boardRepository.delete(board);
        logAuditEvent("BoardDeleted", boardId, userId, "title=" + board.getTitle());
        // TODO: broadcast BOARD_DELETED STOMP message to /topic/board/{boardId} (EN08.1)
    }

    /**
     * Resolves the caller's role on a board.
     *
     * <p>If the caller is the owner, returns {@link BoardRole#OWNER} immediately without
     * a database lookup. Otherwise checks the {@code board_member} table and throws
     * {@link BoardNotFoundException} (to avoid information disclosure) if no membership exists.
     *
     * @param boardId the board UUID
     * @param userId  the caller's {@code public.users.id}
     * @param ownerId the board's owner's {@code public.users.id}
     * @return the caller's role
     * @throws BoardNotFoundException if the caller is not a member or owner of the board
     */
    private BoardRole resolveRole(final UUID boardId, final Long userId, final Long ownerId) {
        if (userId.equals(ownerId)) {
            return BoardRole.OWNER;
        }
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(BoardMember::getRole)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
    }

    /**
     * Converts a board entity to a response DTO for the given caller.
     *
     * @param board  the board entity
     * @param userId the caller's {@code public.users.id} (used to resolve the caller's role)
     * @return the board response
     */
    private BoardResponse toBoardResponse(final Board board, final Long userId) {
        BoardRole role = resolveRole(board.getId(), userId, board.getOwnerId());
        return BoardResponse.from(board, role);
    }

    /**
     * Emits a structured audit log entry for a state-changing board operation.
     *
     * <p>TODO: persist via centralized audit service (EN30.9.5)
     *
     * @param event   the audit event name
     * @param boardId the board UUID
     * @param actorId the {@code public.users.id} of the user who performed the action
     * @param details additional details to include in the log entry
     */
    private void logAuditEvent(
            final String event,
            final UUID boardId,
            final Long actorId,
            final String details) {
        java.util.logging.Logger.getLogger(getClass().getName())
                .info(() -> "AUDIT " + event + " board=" + boardId
                        + " actor=" + actorId + " " + details);
    }
}
