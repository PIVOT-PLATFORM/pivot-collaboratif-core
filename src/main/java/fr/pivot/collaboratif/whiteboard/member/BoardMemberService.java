package fr.pivot.collaboratif.whiteboard.member;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardMemberNotFoundException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.member.dto.MemberResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Business logic for board member management operations.
 *
 * <p>Enforces tenant isolation and role-based access control:
 * only the OWNER may change roles or remove members. Any caller with access
 * (OWNER, EDITOR, VIEWER) may list members.
 */
@Service
@Transactional(readOnly = true)
public class BoardMemberService {

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param boardRepository       repository for board persistence
     * @param boardMemberRepository repository for board membership persistence
     */
    public BoardMemberService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
    }

    /**
     * Returns the full member list for a board.
     *
     * <p>Any member (OWNER, EDITOR, VIEWER) may call this. The OWNER entry is included.
     *
     * @param boardId  the board UUID
     * @param callerId the calling user's {@code public.users.id}
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return list of all members ordered by join date ascending
     * @throws BoardNotFoundException if the board does not exist, belongs to another tenant,
     *                                or the caller is not a member
     */
    public List<MemberResponse> listMembers(
            final UUID boardId,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireAccess(boardId, callerId, board.getOwnerId());
        return boardMemberRepository.findAllByIdBoardIdOrderByJoinedAtAsc(boardId)
                .stream()
                .map(MemberResponse::from)
                .toList();
    }

    /**
     * Changes a member's role; only the OWNER may invoke this.
     *
     * <p>The board owner's own role and promotions to OWNER are both rejected.
     *
     * @param boardId      the board UUID
     * @param targetUserId the {@code public.users.id} of the user whose role is to be changed
     * @param newRole      the new role (EDITOR or VIEWER)
     * @param callerId     the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated member response
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws IllegalArgumentException   if targetUserId is the board owner or newRole is OWNER
     * @throws BoardMemberNotFoundException if targetUserId has no membership on this board
     */
    @Transactional
    public MemberResponse updateRole(
            final UUID boardId,
            final Long targetUserId,
            final BoardRole newRole,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());
        if (targetUserId.equals(board.getOwnerId())) {
            throw new IllegalArgumentException("Cannot modify the board owner's role");
        }
        if (newRole == BoardRole.OWNER) {
            throw new IllegalArgumentException("Cannot promote a member to OWNER");
        }
        BoardMember member = boardMemberRepository
                .findByIdBoardIdAndIdUserId(boardId, targetUserId)
                .orElseThrow(() -> new BoardMemberNotFoundException(boardId, targetUserId));
        member.setRole(newRole);
        BoardMember saved = boardMemberRepository.save(member);
        logAuditEvent("MemberRoleUpdated", boardId, callerId,
                "targetUser=" + targetUserId + " newRole=" + newRole);
        return MemberResponse.from(saved);
    }

    /**
     * Removes a member from the board; only the OWNER may invoke this.
     *
     * <p>The board owner cannot be removed.
     *
     * @param boardId      the board UUID
     * @param targetUserId the {@code public.users.id} of the user to remove
     * @param callerId     the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws IllegalArgumentException   if targetUserId is the board owner
     * @throws BoardMemberNotFoundException if targetUserId has no membership on this board
     */
    @Transactional
    public void removeMember(
            final UUID boardId,
            final Long targetUserId,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());
        if (targetUserId.equals(board.getOwnerId())) {
            throw new IllegalArgumentException("Cannot remove the board owner");
        }
        BoardMember member = boardMemberRepository
                .findByIdBoardIdAndIdUserId(boardId, targetUserId)
                .orElseThrow(() -> new BoardMemberNotFoundException(boardId, targetUserId));
        boardMemberRepository.delete(member);
        logAuditEvent("MemberRemoved", boardId, callerId, "targetUser=" + targetUserId);
    }

    /**
     * Asserts the caller has at least read access (is a member or the owner).
     *
     * @throws BoardNotFoundException if the caller has no membership
     */
    private void requireAccess(final UUID boardId, final Long callerId, final Long ownerId) {
        if (callerId.equals(ownerId)) {
            return;
        }
        boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, callerId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
    }

    /**
     * Asserts the caller is the OWNER.
     *
     * @throws BoardAccessDeniedException if the caller is not the owner
     */
    private void requireOwner(final UUID boardId, final Long callerId, final Long ownerId) {
        requireAccess(boardId, callerId, ownerId);
        if (!callerId.equals(ownerId)) {
            throw new BoardAccessDeniedException(boardId);
        }
    }

    /**
     * Emits a structured audit log entry for a state-changing member operation.
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
