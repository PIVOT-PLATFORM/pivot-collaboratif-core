package fr.pivot.collaboratif.whiteboard.member;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.member.dto.MemberResponse;
import fr.pivot.collaboratif.whiteboard.member.dto.UpdateMemberRoleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing board member management operations under
 * {@code /whiteboard/boards/{boardId}/members}.
 *
 * <p>All endpoints require {@code X-Pivot-User-Id} and {@code X-Pivot-Tenant-Id} headers,
 * resolved into a {@link RequestPrincipal} by {@code RequestPrincipalResolver}.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/boards/{boardId}/members}.
 */
@RestController
@RequestMapping("/whiteboard/boards/{boardId}/members")
public class BoardMemberController {

    private final BoardMemberService boardMemberService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param boardMemberService the member management business logic service
     */
    public BoardMemberController(final BoardMemberService boardMemberService) {
        this.boardMemberService = boardMemberService;
    }

    /**
     * Lists all members of a board, including the owner.
     *
     * <p>Any member (OWNER, EDITOR, VIEWER) may call this endpoint.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     * @return list of members ordered by join date ascending, or HTTP 404 if inaccessible
     */
    @GetMapping
    public List<MemberResponse> listMembers(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        return boardMemberService.listMembers(
                boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Changes a member's role. Only the OWNER may change roles.
     *
     * <p>The board owner's own role cannot be changed, and promotion to OWNER is not allowed.
     *
     * @param boardId      the board UUID from the path
     * @param userId       the target member's UUID from the path
     * @param request      the new role — must not be null and must not be OWNER
     * @param principal    the resolved caller identity
     * @return the updated member response
     */
    @PatchMapping("/{userId}/role")
    public MemberResponse updateRole(
            @PathVariable final UUID boardId,
            @PathVariable final UUID userId,
            @RequestBody @Valid final UpdateMemberRoleRequest request,
            final RequestPrincipal principal) {
        return boardMemberService.updateRole(
                boardId, userId, request.role(), principal.userId(), principal.tenantId());
    }

    /**
     * Removes a member from the board. Only the OWNER may remove members.
     *
     * <p>The board owner cannot be removed.
     *
     * @param boardId   the board UUID from the path
     * @param userId    the target member's UUID to remove from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable final UUID boardId,
            @PathVariable final UUID userId,
            final RequestPrincipal principal) {
        boardMemberService.removeMember(
                boardId, userId, principal.userId(), principal.tenantId());
    }
}
