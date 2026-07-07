package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardPageResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.BoardResponse;
import fr.pivot.collaboratif.whiteboard.board.dto.CreateBoardRequest;
import fr.pivot.collaboratif.whiteboard.board.dto.RenameBoardRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing whiteboard board operations under {@code /whiteboard/boards}.
 *
 * <p>All endpoints require {@code X-Pivot-User-Id} and {@code X-Pivot-Tenant-Id} headers,
 * resolved into a {@link RequestPrincipal} by {@code RequestPrincipalResolver}. Missing or
 * malformed headers result in HTTP 401.
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
     * @param request   the board creation request — must contain a non-blank title of at most
     *                  100 characters
     * @param principal the resolved caller identity (user + tenant)
     * @return the created board with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse create(
            @RequestBody @Valid final CreateBoardRequest request,
            final RequestPrincipal principal) {
        return boardService.create(request.title(), principal.userId(), principal.tenantId());
    }

    /**
     * Lists all boards accessible by the caller (owned or shared), ordered by last update.
     *
     * @param page      zero-based page number (default 0)
     * @param size      page size between 1 and 50 inclusive (default 20)
     * @param principal the resolved caller identity
     * @return paginated board list with total count and navigation metadata
     */
    @GetMapping
    public BoardPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) final int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) final int size,
            final RequestPrincipal principal) {
        return boardService.findAccessible(
                principal.userId(), principal.tenantId(), page, size);
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
     * Renames a board. Only the OWNER may rename a board.
     *
     * @param boardId   the board UUID from the path
     * @param request   the rename request — must contain a non-blank title of at most 100 chars
     * @param principal the resolved caller identity
     * @return the updated board response
     */
    @PatchMapping("/{boardId}")
    public BoardResponse rename(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final RenameBoardRequest request,
            final RequestPrincipal principal) {
        return boardService.rename(
                boardId, request.title(), principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes a board and all its data. Only the OWNER may delete a board.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        boardService.delete(boardId, principal.userId(), principal.tenantId());
    }
}
