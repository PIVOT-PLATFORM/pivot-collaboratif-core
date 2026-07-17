package fr.pivot.collaboratif.whiteboard.importer;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.ImportKlaxoonResponse;
import fr.pivot.collaboratif.whiteboard.importer.dto.UndoImportRequest;
import fr.pivot.collaboratif.whiteboard.importer.dto.UndoImportResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the Klaxoon board import and its undo (US08.13.1) under
 * {@code /whiteboard/boards/{boardId}/import}.
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3). Tenant and user identity
 * always come from the resolved principal — never from the request body or the {@code boardId}
 * path segment beyond scoping the target board (tenant isolation, EN08.3 / anti-IDOR).
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/boards/{boardId}/import/...}. The 50&nbsp;MB body-size cap
 * on {@code /import/klaxoon} is enforced ahead of this controller by
 * {@link ImportBodySizeLimitFilter}, independently of Bean Validation.
 */
@RestController
@RequestMapping("/whiteboard/boards/{boardId}/import")
@Validated
public class WhiteboardImportController {

    private final WhiteboardImportService importService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param importService the import/undo business logic service
     */
    public WhiteboardImportController(final WhiteboardImportService importService) {
        this.importService = importService;
    }

    /**
     * Imports a Klaxoon board export (cards, connectors, frames, custom fields) into
     * {@code boardId}. Requires OWNER or EDITOR; rate limited to 5 calls/minute/board
     * ({@link ImportRateLimitService}).
     *
     * @param boardId   the target board UUID from the path
     * @param request   the validated import payload
     * @param principal the resolved caller identity (user + tenant)
     * @return the created counts and id lists, HTTP 201
     */
    @PostMapping("/klaxoon")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportKlaxoonResponse importKlaxoon(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final ImportKlaxoonRequest request,
            final RequestPrincipal principal) {
        return importService.importKlaxoon(boardId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Undoes a prior import by deleting the cards/connectors/frames it created, strictly scoped
     * to {@code boardId}. Requires OWNER or EDITOR. {@code BoardField}s created by the import are
     * never deleted.
     *
     * @param boardId   the target board UUID from the path
     * @param request   the id lists to delete (from a prior import's response)
     * @param principal the resolved caller identity (user + tenant)
     * @return the actually-deleted counts, HTTP 200
     */
    @PostMapping("/undo")
    @ResponseStatus(HttpStatus.OK)
    public UndoImportResponse undoImport(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final UndoImportRequest request,
            final RequestPrincipal principal) {
        return importService.undoImport(boardId, request, principal.userId(), principal.tenantId());
    }
}
