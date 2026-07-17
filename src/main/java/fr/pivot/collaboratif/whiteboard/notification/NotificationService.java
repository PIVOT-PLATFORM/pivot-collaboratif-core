package fr.pivot.collaboratif.whiteboard.notification;

import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emits in-app notifications for whiteboard sharing and role-governance events (US08.2.5).
 *
 * <p>Persists one {@link Notification} row per event on the current transaction (the caller's
 * service is {@code @Transactional}), so an emission is rolled back with the mutation that
 * triggered it. Bodies are composed in French; role labels follow the spec mapping
 * {@code {VIEWER:'lecteur', EDITOR:'éditeur', OWNER:'propriétaire'}}.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * Creates the service.
     *
     * @param notificationRepository notification persistence
     */
    public NotificationService(final NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Emits a {@link NotificationType#BOARD_SHARED} notification to a newly-invited member.
     *
     * @param board           the board that was shared
     * @param recipientUserId the invited user's {@code public.users.id}
     * @param actorUserId     the inviting user's {@code public.users.id}
     * @param role            the role granted
     */
    @Transactional
    public void notifyBoardShared(
            final Board board, final Long recipientUserId, final Long actorUserId, final BoardRole role) {
        String body = "Le tableau « " + board.getTitle() + " » a été partagé avec vous en tant que "
                + roleLabel(role) + ".";
        save(board, recipientUserId, actorUserId, NotificationType.BOARD_SHARED, body);
    }

    /**
     * Emits a {@link NotificationType#ROLE_CHANGED} notification to a member whose role changed.
     *
     * @param board           the board concerned
     * @param recipientUserId the affected user's {@code public.users.id}
     * @param actorUserId     the acting user's {@code public.users.id}
     * @param role            the new role
     */
    @Transactional
    public void notifyRoleChanged(
            final Board board, final Long recipientUserId, final Long actorUserId, final BoardRole role) {
        String body = "Votre rôle sur le tableau « " + board.getTitle() + " » est désormais "
                + roleLabel(role) + ".";
        save(board, recipientUserId, actorUserId, NotificationType.ROLE_CHANGED, body);
    }

    /**
     * Emits a {@link NotificationType#ACCESS_REVOKED} notification to a member whose access was
     * revoked.
     *
     * @param board           the board concerned
     * @param recipientUserId the removed user's {@code public.users.id}
     * @param actorUserId     the acting user's {@code public.users.id}
     */
    @Transactional
    public void notifyAccessRevoked(
            final Board board, final Long recipientUserId, final Long actorUserId) {
        String body = "Votre accès au tableau « " + board.getTitle() + " » a été révoqué.";
        save(board, recipientUserId, actorUserId, NotificationType.ACCESS_REVOKED, body);
    }

    private void save(
            final Board board,
            final Long recipientUserId,
            final Long actorUserId,
            final NotificationType type,
            final String body) {
        notificationRepository.save(new Notification(
                board.getTenantId(), recipientUserId, actorUserId, board.getId(), type, body));
    }

    /**
     * Returns the French label of a role for notification bodies.
     *
     * @param role the role
     * @return {@code lecteur} / {@code éditeur} / {@code propriétaire}
     */
    private String roleLabel(final BoardRole role) {
        return switch (role) {
            case VIEWER -> "lecteur";
            case EDITOR -> "éditeur";
            case OWNER -> "propriétaire";
        };
    }
}
