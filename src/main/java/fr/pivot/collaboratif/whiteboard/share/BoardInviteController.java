package fr.pivot.collaboratif.whiteboard.share;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.share.dto.InviteShareRequest;
import fr.pivot.collaboratif.whiteboard.share.dto.ShareResponse;
import fr.pivot.collaboratif.whiteboard.share.dto.UpdateShareRoleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for named e-mail invitations and share-role governance (US08.2.5), exposed
 * under {@code /whiteboard/boards/{boardId}/shares} (full path
 * {@code /api/collaboratif/whiteboard/boards/{boardId}/shares}).
 *
 * <p>Distinct from {@link BoardShareController} ({@code /share}), which manages public share
 * <em>link</em> tokens. Here a "share" is a named member row. All endpoints require a valid
 * {@code Authorization: Bearer <token>} header, resolved into a {@link RequestPrincipal}. The
 * caller identity ({@code tenantId}, {@code userId}) comes exclusively from that principal —
 * never from the path or body.
 */
@RestController
@RequestMapping("/whiteboard/boards/{boardId}/shares")
public class BoardInviteController {

    private final BoardInviteService boardInviteService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param boardInviteService the invitation/governance business logic service
     */
    public BoardInviteController(final BoardInviteService boardInviteService) {
        this.boardInviteService = boardInviteService;
    }

    /**
     * Invites a user by e-mail with a role (defaults to VIEWER).
     *
     * @param boardId   the board UUID from the path
     * @param request   the invitation (e-mail + optional role)
     * @param principal the resolved caller identity
     * @return the created/updated share with HTTP 201 Created
     */
    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareResponse invite(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final InviteShareRequest request,
            final RequestPrincipal principal) {
        return boardInviteService.invite(
                boardId, request.email(), request.role(), principal.userId(), principal.tenantId());
    }

    /**
     * Changes a share's role, scoped by {@code (shareId, boardId)}.
     *
     * @param boardId   the board UUID from the path
     * @param shareId   the surrogate share identifier from the path
     * @param request   the new role (required)
     * @param principal the resolved caller identity
     * @return the updated share with HTTP 200 OK
     */
    @PatchMapping("/{shareId}")
    public ShareResponse updateRole(
            @PathVariable final UUID boardId,
            @PathVariable final UUID shareId,
            @RequestBody @Valid final UpdateShareRoleRequest request,
            final RequestPrincipal principal) {
        return boardInviteService.updateRole(
                boardId, shareId, request.role(), principal.userId(), principal.tenantId());
    }

    /**
     * Revokes a share, scoped by {@code (shareId, boardId)}.
     *
     * @param boardId   the board UUID from the path
     * @param shareId   the surrogate share identifier from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable final UUID boardId,
            @PathVariable final UUID shareId,
            final RequestPrincipal principal) {
        boardInviteService.revoke(boardId, shareId, principal.userId(), principal.tenantId());
    }
}
