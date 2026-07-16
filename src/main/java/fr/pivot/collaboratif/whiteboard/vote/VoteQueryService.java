package fr.pivot.collaboratif.whiteboard.vote;

import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.vote.dto.VoteSessionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-side service backing the vote REST endpoints ({@code GET .../vote/current|last}).
 *
 * <p>Every read first resolves the board within the caller's tenant and asserts the caller is a
 * member (owner or shared) — a cross-tenant or non-member board resolves to a
 * {@link BoardNotFoundException} (HTTP 404), never a 403, so the endpoint never confirms the
 * existence of a resource the caller may not see (module anti-IDOR rule). Tenant and user identity
 * come exclusively from the resolved principal.
 */
@Service
@Transactional(readOnly = true)
public class VoteQueryService {

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final VoteSessionRepository voteSessionRepository;
    private final VoteRepository voteRepository;

    /**
     * Creates the service.
     *
     * @param boardRepository       repository used to resolve the board within the caller's tenant
     * @param boardMemberRepository repository used to assert the caller's board membership
     * @param voteSessionRepository repository for {@link VoteSession} lookups
     * @param voteRepository        repository used to load a session's vote tally
     */
    public VoteQueryService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final VoteSessionRepository voteSessionRepository,
            final VoteRepository voteRepository) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.voteSessionRepository = voteSessionRepository;
        this.voteRepository = voteRepository;
    }

    /**
     * Returns the board's currently active vote session (with its live tally), or {@code null}
     * when no vote is running. The full vote list lets the caller compute per-card counts and its
     * own remaining voices client-side.
     *
     * @param boardId  the board UUID from the path
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the caller's {@code public.tenants.id}
     * @return the active session, or {@code null}
     * @throws BoardNotFoundException if the board is unknown, cross-tenant, or the caller is not a
     *                                member
     */
    public VoteSessionResponse current(final UUID boardId, final Long userId, final Long tenantId) {
        requireBoardAccess(boardId, userId, tenantId);
        return voteSessionRepository
                .findByBoardIdAndTenantIdAndStatus(boardId, tenantId, VoteStatus.ACTIVE)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Returns the board's most recently closed vote session (with its final tally), or
     * {@code null} when the board has never held one.
     *
     * @param boardId  the board UUID from the path
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the caller's {@code public.tenants.id}
     * @return the last closed session, or {@code null}
     * @throws BoardNotFoundException if the board is unknown, cross-tenant, or the caller is not a
     *                                member
     */
    public VoteSessionResponse last(final UUID boardId, final Long userId, final Long tenantId) {
        requireBoardAccess(boardId, userId, tenantId);
        return voteSessionRepository
                .findFirstByBoardIdAndTenantIdAndStatusOrderByCreatedAtDesc(boardId, tenantId, VoteStatus.CLOSED)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Resolves the board within the tenant and asserts the caller may access it (owner or member),
     * throwing {@link BoardNotFoundException} otherwise.
     *
     * @param boardId  the board UUID
     * @param userId   the caller's user id
     * @param tenantId the caller's tenant id
     */
    private void requireBoardAccess(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        boolean isMember = board.getOwnerId().equals(userId)
                || boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId).isPresent();
        if (!isMember) {
            throw new BoardNotFoundException(boardId);
        }
    }

    /**
     * Maps a session to its response, loading its vote tally.
     *
     * @param session the session
     * @return the response DTO
     */
    private VoteSessionResponse toResponse(final VoteSession session) {
        return VoteSessionResponse.of(
                session, voteRepository.findAllBySessionIdOrderByCreatedAtAsc(session.getId()));
    }
}
