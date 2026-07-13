package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardPageResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.CreateBoardRequest;
import fr.pivot.collaboratif.whiteboard.board.dto.PatchBoardRequest;
import fr.pivot.collaboratif.whiteboard.board.dto.SaveAsTemplateRequest;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing whiteboard board operations under {@code /whiteboard/boards}.
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into
 * a {@link RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3). Missing, malformed,
 * or rejected tokens result in HTTP 401. Tenant and user identity always come from the
 * resolved principal — never from the request body or a query parameter (tenant isolation,
 * EN08.3 / anti-IDOR).
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/boards}.
 */
@RestController
@RequestMapping("/whiteboard/boards")
@Validated
public class BoardController {

    private final BoardService boardService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param boardService the board business logic service
     */
    public BoardController(final BoardService boardService) {
        this.boardService = boardService;
    }

    /**
     * Creates a new board. The caller is automatically assigned as OWNER.
     *
     * <p>When {@code templateId} is given, the board's canvas is initialized from that
     * whiteboard template's elements (US08.4.1). Omitting it creates a blank board
     * (US08.1.1 behaviour, unchanged).
     *
     * @param request    the board creation request — must contain a non-blank title of at most
     *                   100 characters
     * @param templateId optional raw {@code templateId} query parameter identifying a global
     *                   template to initialize the board's canvas from
     * @param principal  the resolved caller identity (user + tenant)
     * @return the created board with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse create(
            @RequestBody @Valid final CreateBoardRequest request,
            @RequestParam(name = "templateId", required = false) final String templateId,
            final RequestPrincipal principal) {
        return boardService.create(
                request.title(), principal.userId(), principal.tenantId(), templateId);
    }

    /**
     * Lists boards for the caller, ordered by last update.
     *
     * <p>By default returns non-trashed boards accessible to the caller (owned or shared),
     * each with the caller's personal {@code favorite} flag (US08.1.6). When {@code q} is
     * present and non-blank, results are filtered by a case/accent-insensitive substring
     * match on title or description (US08.1.8). When {@code trashed=true}, returns instead the
     * boards owned by the caller that are currently in the trash (US08.1.7), with
     * {@code deletedAt} populated — {@code q} is ignored in that mode.
     *
     * @param page      zero-based page number (default 0)
     * @param size      page size between 1 and 50 inclusive (default 20)
     * @param query     optional search text filtering title/description (US08.1.8)
     * @param trashed   when {@code true}, list the caller's trashed boards instead (US08.1.7)
     * @param principal the resolved caller identity
     * @return paginated board list with total count and navigation metadata
     */
    @GetMapping
    public BoardPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) final int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) final int size,
            @RequestParam(name = "q", required = false) final String query,
            @RequestParam(name = "trashed", defaultValue = "false") final boolean trashed,
            final RequestPrincipal principal) {
        if (trashed) {
            return boardService.findTrashed(
                    principal.userId(), principal.tenantId(), page, size);
        }
        return boardService.findAccessible(
                principal.userId(), principal.tenantId(), query, page, size);
    }

    /**
     * Returns a single board by its identifier, if the caller has access.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     * @return the board with the caller's role, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{boardId}")
    public BoardResponse findById(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        return boardService.findById(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Updates a board's title and/or settings (US08.1.4 rename + US08.2.4 settings). Only the
     * OWNER may update a board; any omitted field is left unchanged.
     *
     * @param boardId   the board UUID from the path
     * @param request   the partial update request
     * @param principal the resolved caller identity
     * @return the updated board response
     */
    @PatchMapping("/{boardId}")
    public BoardResponse patch(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final PatchBoardRequest request,
            final RequestPrincipal principal) {
        return boardService.patch(
                boardId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Soft-deletes a board (moves it to the trash). Only the OWNER may delete a board
     * (US08.1.7). The board leaves normal listings but stays restorable.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.softDelete(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Restores a board out of the trash. Only the OWNER may restore; returns 409 if the board
     * is not currently in the trash (US08.1.7).
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @PostMapping("/{boardId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.restore(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes a board and all its data (cascade). Only the OWNER may purge, and
     * only from the trash; returns 409 if the board is not currently in the trash (US08.1.7).
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{boardId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentDelete(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.permanentDelete(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Marks a board as favorite for the calling user (US08.1.6). Idempotent (upsert): calling
     * it again on an already-favorited board is a no-op. Any board member may favorite.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @PutMapping("/{boardId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFavorite(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.addFavorite(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Removes a board from the calling user's favorites (US08.1.6). Idempotent: calling it on
     * a non-favorited board is a no-op. Only the caller's own favorite marker is affected.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{boardId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavorite(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.removeFavorite(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Resets a board's canvas: deletes all canvas content and broadcasts a {@code RESET}
     * STOMP event to connected participants (US08.2.4). Authorized for OWNER or EDITOR (not
     * VIEWER). Board metadata (title, members, favorites) is preserved.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @PostMapping("/{boardId}/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.reset(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Saves a board's current canvas content as a new tenant-private template (US08.2.4).
     * Only the OWNER may do this.
     *
     * @param boardId   the board UUID from the path
     * @param request   the template name/description
     * @param principal the resolved caller identity
     * @return the created template with HTTP 201 Created
     */
    @PostMapping("/{boardId}/save-as-template")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse saveAsTemplate(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final SaveAsTemplateRequest request,
            final RequestPrincipal principal) {
        return boardService.saveAsTemplate(
                boardId, request, principal.userId(), principal.tenantId());
    }
}
