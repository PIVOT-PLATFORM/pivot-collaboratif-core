package fr.pivot.collaboratif.whiteboard.share;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.share.dto.ShareBoardRequest;
import fr.pivot.collaboratif.whiteboard.share.dto.ShareBoardResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing board share token operations under
 * {@code /whiteboard/boards/{boardId}/share}.
 *
 * <p>Only the OWNER of a board may generate or revoke share tokens.
 */
@RestController
@RequestMapping("/whiteboard/boards/{boardId}/share")
public class BoardShareController {

    private final BoardShareService boardShareService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param boardShareService the share token business logic service
     */
    public BoardShareController(final BoardShareService boardShareService) {
        this.boardShareService = boardShareService;
    }

    /**
     * Generates a new share invitation token for the given board.
     *
     * <p>The plain token is embedded in the {@code shareLink} field of the response.
     * This is the only time the plain token is visible.
     *
     * @param boardId   the board UUID from the path
     * @param request   share parameters (role, optional maxUses, optional ttlDays)
     * @param principal the resolved caller identity
     * @return the generated token metadata and share link with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareBoardResponse generate(
            @PathVariable final UUID boardId,
            @RequestBody @Valid final ShareBoardRequest request,
            final RequestPrincipal principal) {
        return boardShareService.generateToken(
                boardId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Revokes an existing share token.
     *
     * <p>The token is soft-deleted by setting its {@code revokedAt} timestamp.
     *
     * @param boardId   the board UUID from the path
     * @param tokenId   the token UUID to revoke
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable final UUID boardId,
            @PathVariable final UUID tokenId,
            final RequestPrincipal principal) {
        boardShareService.revokeToken(
                boardId, tokenId, principal.userId(), principal.tenantId());
    }
}
