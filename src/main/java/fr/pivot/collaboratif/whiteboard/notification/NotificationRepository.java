package fr.pivot.collaboratif.whiteboard.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Notification} entities.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Returns every notification delivered to a recipient, newest first.
     *
     * @param recipientUserId the recipient's {@code public.users.id}
     * @return the recipient's notifications ordered by creation time descending
     */
    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId);

    /**
     * Returns notifications of a given type delivered to a recipient for a board, newest first —
     * used by tests and future inbox queries.
     *
     * @param recipientUserId the recipient's {@code public.users.id}
     * @param boardId         the related board UUID
     * @param type            the notification type
     * @return the matching notifications ordered by creation time descending
     */
    List<Notification> findByRecipientUserIdAndBoardIdAndTypeOrderByCreatedAtDesc(
            Long recipientUserId, UUID boardId, NotificationType type);
}
