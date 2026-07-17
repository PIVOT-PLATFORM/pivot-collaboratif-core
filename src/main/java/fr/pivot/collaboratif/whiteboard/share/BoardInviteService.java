package fr.pivot.collaboratif.whiteboard.share;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.InviteeNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.notification.NotificationService;
import fr.pivot.collaboratif.whiteboard.share.dto.ShareResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Business logic for named e-mail invitations and share-role governance (US08.2.5, extends F08.2).
 *
 * <p>A "share" is a {@link BoardMember} row (there is no separate table). Every mutation:
 * <ul>
 *   <li>Resolves {@code tenantId} and the manager's {@code userId} exclusively from the request
 *       principal (the caller passes them in from the resolved bearer token — never from path or
 *       body).</li>
 *   <li>Loads the board scoped to the caller's tenant, returning 404 on any cross-tenant or
 *       unknown board (anti-enumeration).</li>
 *   <li>Requires the caller to be a manager (OWNER or EDITOR) via {@link #requireManager}; a
 *       VIEWER is 403, a non-member is 404.</li>
 *   <li>Enforces the role hierarchy: an EDITOR may never assign OWNER, nor modify/revoke a target
 *       that is currently OWNER — the governance check reads the target's <em>current</em> role
 *       from the database, never a client-supplied value.</li>
 *   <li>Scopes {@code PATCH}/{@code DELETE} by {@code (shareId, boardId)} (IDOR guard §6.1).</li>
 * </ul>
 *
 * <p>The board creator ({@code Board.ownerId}) is never reachable through these routes: their own
 * membership row is treated as not-a-share (404 when targeted), and an invitation naming the
 * creator's e-mail is rejected 400. No method ever writes {@code Board.ownerId}.
 */
@Service
@Transactional(readOnly = true)
public class BoardInviteService {

    private final BoardRepository boardRepository;
    private final BoardMemberRepository memberRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final NotificationService notificationService;

    /**
     * Creates the service with all required dependencies.
     *
     * @param boardRepository         board persistence
     * @param memberRepository        board membership (share) persistence
     * @param userDirectoryRepository read-only e-mail resolution against {@code public.users}
     * @param notificationService     in-app notification emitter
     */
    public BoardInviteService(
            final BoardRepository boardRepository,
            final BoardMemberRepository memberRepository,
            final UserDirectoryRepository userDirectoryRepository,
            final NotificationService notificationService) {
        this.boardRepository = boardRepository;
        this.memberRepository = memberRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.notificationService = notificationService;
    }

    /**
     * Invites a user named by e-mail with a role, upserting the {@code (boardId, userId)} share.
     *
     * <p>Refusal order (spec §Notes): (1) an EDITOR requesting {@code role=OWNER} → 403,
     * (2) unknown e-mail → 404, (3) self-invitation → 400, (4) e-mail of the creator → 400.
     * On success: a new share emits {@code BOARD_SHARED}; an existing share whose role changes
     * emits {@code ROLE_CHANGED}; a re-invitation with the same role is a functional no-op with
     * no notification.
     *
     * @param boardId      the board UUID
     * @param email        the invitee's e-mail
     * @param requestedRole the requested role, or {@code null} to default to VIEWER
     * @param callerId     the manager's {@code public.users.id} (from the resolved token)
     * @param tenantId     the manager's {@code public.tenants.id} (from the resolved token)
     * @return the created/updated share
     * @throws BoardNotFoundException     if the board is unknown or cross-tenant (404)
     * @throws BoardAccessDeniedException if the caller is a VIEWER, or an EDITOR assigning OWNER
     *                                    (403)
     * @throws InviteeNotFoundException   if the e-mail resolves to no active user of the tenant
     *                                    (404)
     * @throws IllegalArgumentException   on self-invitation or invitation of the creator (400)
     */
    @Transactional
    public ShareResponse invite(
            final UUID boardId,
            final String email,
            final BoardRole requestedRole,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole callerRole = requireManager(board, callerId);
        BoardRole role = requestedRole != null ? requestedRole : BoardRole.VIEWER;

        // (1) An EDITOR can never grant the OWNER role.
        if (callerRole == BoardRole.EDITOR && role == BoardRole.OWNER) {
            throw new BoardAccessDeniedException(boardId);
        }
        // (2) The e-mail must resolve to an active user of the same tenant.
        UserDirectoryEntry invitee = userDirectoryRepository
                .findByEmailIgnoreCaseAndTenantIdAndActiveTrue(email, tenantId)
                .orElseThrow(InviteeNotFoundException::new);
        Long inviteeId = invitee.getId();
        // (3) A manager cannot invite themselves.
        if (inviteeId.equals(callerId)) {
            throw new IllegalArgumentException("Vous ne pouvez pas vous inviter vous-même");
        }
        // (4) The creator already owns the board and has no share row.
        if (inviteeId.equals(board.getOwnerId())) {
            throw new IllegalArgumentException("Cet utilisateur est déjà propriétaire du board");
        }

        BoardMember existing = memberRepository
                .findByIdBoardIdAndIdUserId(boardId, inviteeId)
                .orElse(null);
        if (existing == null) {
            BoardMember created = memberRepository.save(
                    new BoardMember(new BoardMemberId(boardId, inviteeId), role, Instant.now()));
            notificationService.notifyBoardShared(board, inviteeId, callerId, role);
            logAudit("BoardInviteCreated", boardId, callerId, "invitee=" + inviteeId + " role=" + role);
            return ShareResponse.from(created);
        }
        if (existing.getRole() != role) {
            existing.setRole(role);
            BoardMember saved = memberRepository.save(existing);
            notificationService.notifyRoleChanged(board, inviteeId, callerId, role);
            logAudit("BoardInviteRoleChanged", boardId, callerId, "invitee=" + inviteeId + " role=" + role);
            return ShareResponse.from(saved);
        }
        // Same role → functional no-op, no notification.
        return ShareResponse.from(existing);
    }

    /**
     * Changes a share's role, scoped by {@code (shareId, boardId)}; emits {@code ROLE_CHANGED}
     * systematically (no comparison with the previous role, unlike {@link #invite}).
     *
     * @param boardId  the board UUID from the path
     * @param shareId  the surrogate share identifier from the path
     * @param newRole  the new role (required)
     * @param callerId the manager's {@code public.users.id}
     * @param tenantId the manager's {@code public.tenants.id}
     * @return the updated share
     * @throws BoardNotFoundException     if the board is unknown/cross-tenant, or the share does
     *                                    not belong to the board, or targets the creator (404)
     * @throws BoardAccessDeniedException if the caller is a VIEWER, or an EDITOR touching an OWNER
     *                                    target or assigning OWNER (403)
     */
    @Transactional
    public ShareResponse updateRole(
            final UUID boardId,
            final UUID shareId,
            final BoardRole newRole,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole callerRole = requireManager(board, callerId);
        BoardMember target = requireShare(shareId, boardId, board);

        if (callerRole == BoardRole.EDITOR
                && (target.getRole() == BoardRole.OWNER || newRole == BoardRole.OWNER)) {
            throw new BoardAccessDeniedException(boardId);
        }
        target.setRole(newRole);
        BoardMember saved = memberRepository.save(target);
        notificationService.notifyRoleChanged(board, target.getId().getUserId(), callerId, newRole);
        logAudit("BoardShareRoleChanged", boardId, callerId,
                "share=" + shareId + " role=" + newRole);
        return ShareResponse.from(saved);
    }

    /**
     * Revokes a share, scoped by {@code (shareId, boardId)}; emits {@code ACCESS_REVOKED} to the
     * former member.
     *
     * @param boardId  the board UUID from the path
     * @param shareId  the surrogate share identifier from the path
     * @param callerId the manager's {@code public.users.id}
     * @param tenantId the manager's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board is unknown/cross-tenant, or the share does
     *                                    not belong to the board, or targets the creator (404)
     * @throws BoardAccessDeniedException if the caller is a VIEWER, or an EDITOR revoking an OWNER
     *                                    target (403)
     */
    @Transactional
    public void revoke(
            final UUID boardId,
            final UUID shareId,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        BoardRole callerRole = requireManager(board, callerId);
        BoardMember target = requireShare(shareId, boardId, board);

        if (callerRole == BoardRole.EDITOR && target.getRole() == BoardRole.OWNER) {
            throw new BoardAccessDeniedException(boardId);
        }
        Long removedUserId = target.getId().getUserId();
        memberRepository.delete(target);
        notificationService.notifyAccessRevoked(board, removedUserId, callerId);
        logAudit("BoardShareRevoked", boardId, callerId, "share=" + shareId + " user=" + removedUserId);
    }

    /**
     * Resolves the caller's effective managing role on the board.
     *
     * @return {@link BoardRole#OWNER} or {@link BoardRole#EDITOR}
     * @throws BoardNotFoundException     if the caller is not a member (404, anti-enumeration)
     * @throws BoardAccessDeniedException if the caller is a VIEWER (403)
     */
    private BoardRole requireManager(final Board board, final Long callerId) {
        if (callerId.equals(board.getOwnerId())) {
            return BoardRole.OWNER;
        }
        BoardMember membership = memberRepository
                .findByIdBoardIdAndIdUserId(board.getId(), callerId)
                .orElseThrow(() -> new BoardNotFoundException(board.getId()));
        if (membership.getRole() == BoardRole.VIEWER) {
            throw new BoardAccessDeniedException(board.getId());
        }
        return membership.getRole();
    }

    /**
     * Loads the share row scoped by {@code (shareId, boardId)} and rejects the creator's own row.
     *
     * @throws BoardNotFoundException if the share does not belong to the board, or targets the
     *                                board creator (404 — the creator is never reachable via
     *                                share routes)
     */
    private BoardMember requireShare(final UUID shareId, final UUID boardId, final Board board) {
        BoardMember target = memberRepository
                .findByShareIdAndIdBoardId(shareId, boardId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        if (target.getId().getUserId().equals(board.getOwnerId())) {
            throw new BoardNotFoundException(boardId);
        }
        return target;
    }

    private void logAudit(
            final String event, final UUID boardId, final Long actorId, final String details) {
        java.util.logging.Logger.getLogger(getClass().getName())
                .info(() -> "AUDIT " + event + " board=" + boardId + " actor=" + actorId + " " + details);
    }
}
