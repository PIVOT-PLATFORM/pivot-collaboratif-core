package fr.pivot.collaboratif.whiteboard.vote;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.vote.dto.VoteSessionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing read-only access to a board's dot-voting sessions
 * (Vote / dot-voting feature). Complements the STOMP mutation channel ({@code vote:*}, see
 * {@link VoteActionService}) so a client that connects mid-vote — or reopens the board — can
 * rehydrate the live vote and the last result without waiting for the next broadcast (frontend
 * {@code board.store.ts#loadVote}).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3). Tenant and user identity
 * always come from the resolved principal — never from the request. A board that is unknown,
 * cross-tenant, or that the caller is not a member of resolves to HTTP 404 (never 403), so the
 * endpoint never confirms a resource the caller may not see.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/boards/{boardId}/vote/...}.
 */
@RestController
@RequestMapping("/whiteboard/boards/{boardId}/vote")
public class VoteController {

    private final VoteQueryService voteQueryService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param voteQueryService the vote read service
     */
    public VoteController(final VoteQueryService voteQueryService) {
        this.voteQueryService = voteQueryService;
    }

    /**
     * Returns the board's currently active vote session, or {@code null} (HTTP 200 with an empty
     * body) when no vote is running. Includes every dot cast so the client can derive per-card
     * counts and its own remaining voices.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     * @return the active session, or {@code null} if none
     */
    @GetMapping("/current")
    public VoteSessionResponse current(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        return voteQueryService.current(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Returns the board's most recently closed vote session, or {@code null} (HTTP 200 with an
     * empty body) when the board has never held one.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     * @return the last closed session, or {@code null} if none
     */
    @GetMapping("/last")
    public VoteSessionResponse last(
            @PathVariable final UUID boardId,
            final RequestPrincipal principal) {
        return voteQueryService.last(boardId, principal.userId(), principal.tenantId());
    }
}
