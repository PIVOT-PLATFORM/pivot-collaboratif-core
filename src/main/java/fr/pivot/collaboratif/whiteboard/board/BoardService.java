package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.BoardNotInTrashException;
import fr.pivot.collaboratif.exception.InvalidActivityException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardCardResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardPageResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.PatchBoardRequest;
import fr.pivot.collaboratif.whiteboard.board.dto.SaveAsTemplateRequest;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.WhiteboardBroadcastService;
import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplate;
import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplateService;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for whiteboard board operations.
 *
 * <p>All read operations are wrapped in a read-only transaction; write operations
 * use a full read-write transaction. The service enforces tenant isolation and role-based
 * access control for every board operation.
 *
 * <p>Tenant and user identity are always supplied by the controller from the resolved
 * {@code RequestPrincipal} (EN08.3) — never from the request body or a query parameter.
 * Cross-tenant access resolves to a 404 (via {@link BoardNotFoundException}) rather than a
 * 403, to avoid confirming the existence of a resource in another tenant.
 */
@Service
@Transactional(readOnly = true)
public class BoardService {

    private static final Logger LOG = LoggerFactory.getLogger(BoardService.class);

    /** Maximum allowed page size to prevent unbounded result sets. */
    private static final int MAX_PAGE_SIZE = 50;

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final BoardFavoriteRepository boardFavoriteRepository;
    private final CanvasEventRepository canvasEventRepository;
    private final CardRepository cardRepository;
    private final WhiteboardModuleCheck moduleCheck;
    private final WhiteboardTemplateService templateService;
    private final WhiteboardBroadcastService broadcastService;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with all required dependencies.
     *
     * @param boardRepository         repository for board persistence
     * @param boardMemberRepository   repository for board membership persistence
     * @param boardFavoriteRepository repository for per-user favorites (US08.1.6)
     * @param canvasEventRepository   repository for canvas events (US08.2.4 reset/save-as-template)
     * @param cardRepository          repository for durable card state, read-only here, used to
     *                                embed cards with field values in {@code GET
     *                                /whiteboard/boards/{boardId}} (US08.1.9)
     * @param moduleCheck             check for whiteboard module activation
     * @param templateService         service resolving templates and initializing a board's
     *                                canvas from one, and snapshotting a board into a new
     *                                template (US08.4.1 / US08.2.4)
     * @param broadcastService        STOMP broadcaster for board reset events (US08.2.4)
     * @param objectMapper            Jackson mapper used to parse a card's opaque metadata
     *                                cache into the {@code fieldValues} wire map (US08.1.9)
     */
    public BoardService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final BoardFavoriteRepository boardFavoriteRepository,
            final CanvasEventRepository canvasEventRepository,
            final CardRepository cardRepository,
            final WhiteboardModuleCheck moduleCheck,
            final WhiteboardTemplateService templateService,
            final WhiteboardBroadcastService broadcastService,
            final ObjectMapper objectMapper) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.boardFavoriteRepository = boardFavoriteRepository;
        this.canvasEventRepository = canvasEventRepository;
        this.cardRepository = cardRepository;
        this.moduleCheck = moduleCheck;
        this.templateService = templateService;
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // create()
    // -------------------------------------------------------------------------

    /**
     * Creates a new board and assigns the caller as OWNER.
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
     * @param title      board title (1–100 chars, validated at the controller layer)
     * @param userId     calling user's {@code public.users.id}
     * @param tenantId   calling tenant's {@code public.tenants.id}
     * @param templateId raw {@code templateId} request parameter, or {@code null}/blank for
     *                   a blank board (US08.1.1 behaviour, unchanged)
     * @return the created board as a response record
     * @throws WhiteboardModuleDisabledException                        if the module is inactive
     * @throws fr.pivot.collaboratif.exception.InvalidTemplateIdException if {@code templateId}
     *                                                                    is not a valid UUID
     * @throws fr.pivot.collaboratif.exception.TemplateNotFoundException  if {@code templateId}
     *                                                                    does not resolve
     */
    @Transactional
    public BoardResponse create(
            final String title, final Long userId, final Long tenantId, final String templateId) {
        return create(title, userId, tenantId, templateId, null, null, null);
    }

    /**
     * Creates a new board and assigns the caller as OWNER, with the full creation contract
     * (US08.1.9, parity §2.2, line 313): optional template initialization (US08.4.1) plus
     * optional {@code maxParticipants}/{@code enabledActivities}/{@code coverImage} settings
     * (the same fields {@code PATCH} already accepts, US08.2.4), persisted directly on the
     * created board rather than requiring a follow-up {@code PATCH} call.
     *
     * <p>Validation order: the module-activation check, then {@code enabledActivities}
     * whitelist validation (if present), both <strong>before</strong> any row is persisted —
     * an invalid activity code never leaves a partial board behind.
     *
     * @param title             board title (1–100 chars, validated at the controller layer)
     * @param userId            calling user's {@code public.users.id}
     * @param tenantId          calling tenant's {@code public.tenants.id}
     * @param templateId        raw {@code templateId} request parameter, or {@code null}/blank
     *                          for a blank board (US08.1.1 behaviour, unchanged)
     * @param maxParticipants   optional maximum simultaneous participant count (strictly
     *                          positive, validated at the controller layer), or {@code null}
     * @param enabledActivities optional whitelisted activity codes, or {@code null}
     * @param coverImage        optional cover image URL/string, or {@code null}
     * @return the created board as a response record
     * @throws WhiteboardModuleDisabledException                        if the module is inactive
     * @throws InvalidActivityException                                  if an unknown activity
     *                                                                    code is supplied
     * @throws fr.pivot.collaboratif.exception.InvalidTemplateIdException if {@code templateId}
     *                                                                    is not a valid UUID
     * @throws fr.pivot.collaboratif.exception.TemplateNotFoundException  if {@code templateId}
     *                                                                    does not resolve
     */
    @Transactional
    public BoardResponse create(
            final String title,
            final Long userId,
            final Long tenantId,
            final String templateId,
            final Integer maxParticipants,
            final List<String> enabledActivities,
            final String coverImage) {
        if (!moduleCheck.isEnabled(tenantId)) {
            throw new WhiteboardModuleDisabledException(tenantId);
        }
        if (enabledActivities != null) {
            validateActivities(enabledActivities);
        }
        WhiteboardTemplate template = null;
        if (templateId != null && !templateId.isBlank()) {
            template = templateService.resolveGlobalTemplate(templateId);
        }
        Instant now = Instant.now();
        Board newBoard = new Board(title, tenantId, userId, now);
        if (maxParticipants != null) {
            newBoard.setMaxParticipants(maxParticipants);
        }
        if (enabledActivities != null) {
            newBoard.setEnabledActivities(enabledActivities);
        }
        if (coverImage != null) {
            newBoard.setCoverImage(coverImage);
        }
        Board board = boardRepository.save(newBoard);
        BoardMemberId memberId = new BoardMemberId(board.getId(), userId);
        boardMemberRepository.save(new BoardMember(memberId, BoardRole.OWNER, now));
        if (template != null) {
            templateService.initializeBoard(template, board.getId(), tenantId, userId);
        }
        // A freshly created board has no shares yet — 0, no query needed.
        return BoardResponse.from(board, BoardRole.OWNER, false, 0);
    }

    // -------------------------------------------------------------------------
    // list / read
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated list of non-trashed boards accessible by the caller (owned or
     * shared), optionally filtered by a case/accent-insensitive search over title and
     * description (US08.1.8). Each returned board carries the caller's personal
     * {@code favorite} flag (US08.1.6).
     *
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @param query    optional search text (title/description substring), or {@code null}/blank
     * @param page     zero-based page number
     * @param size     requested page size (capped at {@link #MAX_PAGE_SIZE})
     * @return paginated board list with metadata
     * @throws IllegalArgumentException if {@code size} is zero or negative
     */
    public BoardPageResponse findAccessible(
            final Long userId,
            final Long tenantId,
            final String query,
            final int page,
            final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive");
        }
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
                page, effectiveSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        String normalizedSearch = normalizeSearch(query);
        Page<Board> boardPage =
                boardRepository.findAccessibleByUser(userId, tenantId, normalizedSearch, pageable);
        List<Board> content = boardPage.getContent();
        Set<UUID> favoritedIds = favoritedIdsIn(userId, content);
        Map<UUID, BoardRole> rolesByBoard = rolesIn(userId, content);
        Map<UUID, Integer> shareCounts = shareCountsIn(content);
        List<BoardResponse> responses = content.stream()
                .map(b -> BoardResponse.from(
                        b,
                        roleFor(b, userId, rolesByBoard),
                        favoritedIds.contains(b.getId()),
                        shareCounts.getOrDefault(b.getId(), 0)))
                .toList();
        return new BoardPageResponse(
                responses,
                boardPage.getTotalElements(),
                boardPage.getTotalPages(),
                boardPage.getNumber(),
                boardPage.hasNext());
    }

    /**
     * Returns a paginated list of trashed boards owned by the caller (US08.1.7).
     *
     * <p>Only the OWNER of a board sees it in the trash — membership is not enough.
     * {@code deletedAt} is populated in every returned response.
     *
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @param page     zero-based page number
     * @param size     requested page size (capped at {@link #MAX_PAGE_SIZE})
     * @return paginated trashed board list with metadata
     * @throws IllegalArgumentException if {@code size} is zero or negative
     */
    public BoardPageResponse findTrashed(
            final Long userId, final Long tenantId, final int page, final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive");
        }
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
                page, effectiveSize, Sort.by(Sort.Direction.DESC, "deletedAt"));
        Page<Board> boardPage = boardRepository
                .findByOwnerIdAndTenantIdAndDeletedAtIsNotNull(userId, tenantId, pageable);
        List<Board> content = boardPage.getContent();
        Map<UUID, Integer> shareCounts = shareCountsIn(content);
        List<BoardResponse> responses = content.stream()
                .map(b -> BoardResponse.forTrash(
                        b, BoardRole.OWNER, shareCounts.getOrDefault(b.getId(), 0)))
                .toList();
        return new BoardPageResponse(
                responses,
                boardPage.getTotalElements(),
                boardPage.getTotalPages(),
                boardPage.getNumber(),
                boardPage.hasNext());
    }

    /**
     * Returns a single non-trashed board if the caller has access to it.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return board response for the caller, with the caller's personal favorite flag
     * @throws BoardNotFoundException if the board does not exist, is trashed, belongs to
     *                                another tenant, or the caller is not a member or owner
     */
    public BoardResponse findById(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireAccessibleBoard(boardId, tenantId);
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        boolean favorite = boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, userId);
        List<BoardCardResponse> cards = cardRepository
                .findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantId)
                .stream()
                .map(this::toCardResponse)
                .toList();
        return BoardResponse.withCards(board, role, favorite, shareCountOf(boardId), cards);
    }

    // -------------------------------------------------------------------------
    // patch (rename + settings, US08.1.4 + US08.2.4)
    // -------------------------------------------------------------------------

    /**
     * Applies a partial update to a board's title and/or settings; OWNER only.
     *
     * <p>Any field left {@code null} in {@code request} is unchanged. {@code enabledActivities},
     * if present, must be a subset of the known activity whitelist ({@link BoardActivity}).
     *
     * @param boardId  the board UUID
     * @param request  the partial update payload (validated at the controller layer)
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return the updated board response (favorite flag resolved for the caller)
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws InvalidActivityException   if an unknown activity code is supplied
     */
    @Transactional
    public BoardResponse patch(
            final UUID boardId,
            final PatchBoardRequest request,
            final Long userId,
            final Long tenantId) {
        Board board = requireOwnedBoard(boardId, userId, tenantId);
        if (request.title() != null) {
            board.setTitle(request.title());
        }
        if (request.description() != null) {
            board.setDescription(request.description());
        }
        if (request.coverImage() != null) {
            board.setCoverImage(request.coverImage());
        }
        if (request.maxParticipants() != null) {
            board.setMaxParticipants(request.maxParticipants());
        }
        if (request.enabledActivities() != null) {
            validateActivities(request.enabledActivities());
            board.setEnabledActivities(request.enabledActivities());
        }
        Board saved = boardRepository.save(board);
        logAuditEvent("BoardUpdated", boardId, userId, "title=" + saved.getTitle());
        boolean favorite = boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, userId);
        return BoardResponse.from(saved, BoardRole.OWNER, favorite, shareCountOf(boardId));
    }

    // -------------------------------------------------------------------------
    // soft-delete / restore / permanent-delete (US08.1.7)
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes a board (moves it to the trash by setting {@code deletedAt}); OWNER only.
     *
     * <p>The board disappears from normal listings but stays restorable and its data is
     * preserved (no cascade delete). Idempotency note: an already-trashed board is not
     * re-accessible via {@link #requireOwnedBoard}, so a second delete resolves to 404.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     */
    @Transactional
    public void softDelete(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireOwnedBoard(boardId, userId, tenantId);
        board.setDeletedAt(Instant.now());
        boardRepository.save(board);
        logAuditEvent("BoardDeleted", boardId, userId, "title=" + board.getTitle());
    }

    /**
     * Restores a board out of the trash ({@code deletedAt = null}); OWNER only.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board does not exist or is cross-tenant
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws BoardNotInTrashException   if the board is not currently in the trash
     */
    @Transactional
    public void restore(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireOwnedTrashCandidate(boardId, userId, tenantId);
        if (!board.isDeleted()) {
            throw new BoardNotInTrashException(boardId);
        }
        board.setDeletedAt(null);
        boardRepository.save(board);
        logAuditEvent("BoardRestored", boardId, userId, "title=" + board.getTitle());
    }

    /**
     * Permanently deletes a board and all its data (cascade via FK); OWNER only, and only
     * while the board is in the trash (US08.1.7 — one may only purge from the trash).
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board does not exist or is cross-tenant
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws BoardNotInTrashException   if the board is not currently in the trash
     */
    @Transactional
    public void permanentDelete(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireOwnedTrashCandidate(boardId, userId, tenantId);
        if (!board.isDeleted()) {
            throw new BoardNotInTrashException(boardId);
        }
        boardRepository.delete(board);
        logAuditEvent("BoardPurged", boardId, userId, "title=" + board.getTitle());
    }

    // -------------------------------------------------------------------------
    // favorites (US08.1.6)
    // -------------------------------------------------------------------------

    /**
     * Marks a board as favorite for the calling user (US08.1.6). Idempotent: a no-op if the
     * board is already favorited. Any board member (owner/editor/viewer) may favorite.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException if the board is inaccessible to the caller
     */
    @Transactional
    public void addFavorite(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireAccessibleBoard(boardId, tenantId);
        // resolveRole throws 404 (BoardNotFoundException) if the caller is not a member.
        resolveRole(boardId, userId, board.getOwnerId());
        if (!boardFavoriteRepository.existsByIdBoardIdAndIdUserId(boardId, userId)) {
            boardFavoriteRepository.save(
                    new BoardFavorite(new BoardFavoriteId(boardId, userId), Instant.now()));
        }
    }

    /**
     * Removes a board from the calling user's favorites (US08.1.6). Idempotent: a no-op if
     * the board was not favorited. Any board member may unfavorite their own marker.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException if the board is inaccessible to the caller
     */
    @Transactional
    public void removeFavorite(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireAccessibleBoard(boardId, tenantId);
        resolveRole(boardId, userId, board.getOwnerId());
        boardFavoriteRepository.deleteByIdBoardIdAndIdUserId(boardId, userId);
    }

    // -------------------------------------------------------------------------
    // reset + save-as-template (US08.2.4)
    // -------------------------------------------------------------------------

    /**
     * Resets a board's canvas: deletes all persisted DRAW events and broadcasts a
     * {@code RESET} STOMP message to connected participants (US08.2.4). Board metadata
     * (title, members, favorites) is untouched.
     *
     * <p>Authorized for OWNER <em>or</em> EDITOR (not VIEWER) — deviating from the strict
     * OWNER-only rule of the other settings actions, per the API contract.
     *
     * @param boardId  the board UUID
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is a VIEWER
     */
    @Transactional
    public void reset(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireAccessibleBoard(boardId, tenantId);
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        if (role != BoardRole.OWNER && role != BoardRole.EDITOR) {
            throw new BoardAccessDeniedException(boardId);
        }
        canvasEventRepository.deleteAllByBoardIdAndTenantId(boardId, tenantId);
        board.setUpdatedAt(Instant.now());
        boardRepository.save(board);
        logAuditEvent("BoardReset", boardId, userId, "role=" + role);
        broadcastService.broadcastReset(boardId, userId);
    }

    /**
     * Snapshots a board's current canvas content into a new tenant-private template
     * (US08.2.4); OWNER only.
     *
     * @param boardId  the board UUID
     * @param request  the template name/description (validated at the controller layer)
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return the created template as a response record
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     */
    @Transactional
    public TemplateResponse saveAsTemplate(
            final UUID boardId,
            final SaveAsTemplateRequest request,
            final Long userId,
            final Long tenantId) {
        requireOwnedBoard(boardId, userId, tenantId);
        WhiteboardTemplate template = templateService.createFromBoard(
                boardId, tenantId, request.name(), request.description());
        logAuditEvent("BoardSavedAsTemplate", boardId, userId, "template=" + template.getId());
        return TemplateResponse.from(template);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Loads a non-trashed board scoped to the tenant, or throws 404.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the board
     * @throws BoardNotFoundException if not found, trashed, or cross-tenant
     */
    private Board requireAccessibleBoard(final UUID boardId, final Long tenantId) {
        return boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
    }

    /**
     * Loads a non-trashed, tenant-scoped board and asserts the caller is its OWNER.
     *
     * @param boardId  the board UUID
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the owned board
     * @throws BoardNotFoundException     if not found, trashed, cross-tenant, or the caller
     *                                    is not a member (anti-enumeration 404)
     * @throws BoardAccessDeniedException if the caller is a member but not the OWNER
     */
    private Board requireOwnedBoard(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = requireAccessibleBoard(boardId, tenantId);
        BoardRole role = resolveRole(boardId, userId, board.getOwnerId());
        if (role != BoardRole.OWNER) {
            throw new BoardAccessDeniedException(boardId);
        }
        return board;
    }

    /**
     * Loads a tenant-scoped board regardless of trash status and asserts the caller is its
     * OWNER — used by restore/permanent-delete which act on trashed boards.
     *
     * @param boardId  the board UUID
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the board (trashed or not)
     * @throws BoardNotFoundException     if not found or cross-tenant
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     */
    private Board requireOwnedTrashCandidate(
            final UUID boardId, final Long userId, final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        if (!userId.equals(board.getOwnerId())) {
            throw new BoardAccessDeniedException(boardId);
        }
        return board;
    }

    /**
     * Resolves the caller's role on a board.
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
     * Resolves the caller's role on one board of a listing from the pre-batched role map (see
     * {@link #rolesIn}). Owners short-circuit without a map lookup; for a non-owner the membership
     * row is guaranteed present because {@code findAccessibleByUser} only returns owned-or-member
     * boards — the {@link BoardNotFoundException} fallback preserves the exact semantics of the
     * per-row {@link #resolveRole} it replaces, defensively covering any impossible gap.
     *
     * @param board        the board entity
     * @param userId       the caller's {@code public.users.id}
     * @param rolesByBoard the caller's role per board id, from {@link #rolesIn}
     * @return the caller's role on {@code board}
     * @throws BoardNotFoundException if the caller is neither owner nor a listed member
     */
    private BoardRole roleFor(
            final Board board, final Long userId, final Map<UUID, BoardRole> rolesByBoard) {
        if (userId.equals(board.getOwnerId())) {
            return BoardRole.OWNER;
        }
        BoardRole role = rolesByBoard.get(board.getId());
        if (role == null) {
            throw new BoardNotFoundException(board.getId());
        }
        return role;
    }

    /**
     * Resolves the caller's role on every board of a page in one query — the batch counterpart of
     * {@link #resolveRole}, avoiding an N+1 membership lookup per row. Owners are omitted (they are
     * resolved without a lookup in {@link #roleFor}); only membership rows are fetched.
     *
     * @param userId the caller's {@code public.users.id}
     * @param boards the page of boards
     * @return the caller's {@link BoardRole} keyed by board id, for boards where a membership exists
     */
    private Map<UUID, BoardRole> rolesIn(final Long userId, final List<Board> boards) {
        if (boards.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = new HashSet<>(boards.size());
        for (Board b : boards) {
            ids.add(b.getId());
        }
        Map<UUID, BoardRole> byBoard = new HashMap<>(boards.size());
        for (BoardMember m : boardMemberRepository.findAllByIdBoardIdInAndIdUserId(ids, userId)) {
            byBoard.put(m.getId().getBoardId(), m.getRole());
        }
        return byBoard;
    }

    /**
     * Counts active shares for every board of a page in one grouped query — the batch counterpart
     * of {@link #shareCountOf}, avoiding an N+1 count per row. Boards with zero shares are absent
     * from the map (callers default them to {@code 0}).
     *
     * @param boards the page of boards
     * @return the active-share count keyed by board id, for boards with at least one share
     */
    private Map<UUID, Integer> shareCountsIn(final List<Board> boards) {
        if (boards.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = new HashSet<>(boards.size());
        for (Board b : boards) {
            ids.add(b.getId());
        }
        Map<UUID, Integer> byBoard = new HashMap<>(boards.size());
        for (BoardMemberRepository.BoardShareCount row
                : boardMemberRepository.countSharesGroupedByBoard(ids, BoardRole.OWNER)) {
            byBoard.put(row.getBoardId(), (int) row.getShareCount());
        }
        return byBoard;
    }

    /**
     * Counts active shares (members other than the owner) on a board (US08.1.9, parity §2.2).
     *
     * @param boardId the board UUID
     * @return the number of shares, as an {@code int} (never large enough to overflow in
     *         practice — a board's membership is bounded by its {@code maxParticipants}/UI
     *         constraints, not an unbounded collection)
     */
    private int shareCountOf(final UUID boardId) {
        return (int) boardMemberRepository.countByIdBoardIdAndRoleNot(boardId, BoardRole.OWNER);
    }

    /**
     * Maps a persisted {@link Card} to its {@link BoardCardResponse} wire shape for {@code GET
     * /whiteboard/boards/{boardId}} (US08.1.9), parsing the opaque {@code meta} JSONB column
     * (if present) into the {@code fieldValues} map — mirrors {@code
     * CanvasActionService#toDto}'s equivalent parsing for the WebSocket wire shape.
     *
     * @param card the persisted card
     * @return the corresponding {@link BoardCardResponse}
     */
    @SuppressWarnings("unchecked")
    private BoardCardResponse toCardResponse(final Card card) {
        Map<String, Object> fieldValues = null;
        if (card.getMeta() != null) {
            try {
                fieldValues = objectMapper.readValue(card.getMeta(), Map.class);
            } catch (Exception e) {
                LOG.warn("Could not parse card meta JSON as fieldValues: cardId={} error={}",
                        card.getId(), e.getMessage());
            }
        }
        return BoardCardResponse.of(card, fieldValues);
    }

    /**
     * Resolves which of the given boards are favorited by the user, in a single query.
     *
     * @param userId the caller's {@code public.users.id}
     * @param boards the boards to check
     * @return the set of favorited board ids
     */
    private Set<UUID> favoritedIdsIn(final Long userId, final List<Board> boards) {
        if (boards.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>(boards.size());
        for (Board b : boards) {
            ids.add(b.getId());
        }
        return new HashSet<>(boardFavoriteRepository.findFavoritedBoardIds(userId, ids));
    }

    /**
     * Validates that every activity code is part of the known whitelist ({@link BoardActivity}).
     *
     * @param activities the activity codes to validate
     * @throws InvalidActivityException on the first unknown code
     */
    private void validateActivities(final List<String> activities) {
        for (String activity : activities) {
            if (activity == null || !BoardActivity.isKnown(activity)) {
                throw new InvalidActivityException(String.valueOf(activity));
            }
        }
    }

    /**
     * Normalizes a search term for case/accent-insensitive matching: trims, lower-cases, and
     * strips diacritics (NFD decomposition + combining-mark removal), mirroring the SQL
     * {@code LOWER(unaccent(...))} applied on the column side.
     *
     * @param query the raw search term, or {@code null}
     * @return the normalized term, or {@code null} if the input was {@code null}/blank
     */
    private String normalizeSearch(final String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String stripped = Normalizer.normalize(query.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT);
    }

    /**
     * Emits a structured audit log entry for a state-changing board operation.
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
