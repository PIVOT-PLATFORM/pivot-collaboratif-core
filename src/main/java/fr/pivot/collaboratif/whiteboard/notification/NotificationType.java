package fr.pivot.collaboratif.whiteboard.notification;

/**
 * Type of in-app notification emitted by whiteboard sharing and role governance (US08.2.5).
 *
 * <ul>
 *   <li>{@link #BOARD_SHARED} — a board was shared with the recipient for the first time.</li>
 *   <li>{@link #ROLE_CHANGED} — the recipient's role on a board was changed.</li>
 *   <li>{@link #ACCESS_REVOKED} — the recipient's access to a board was revoked.</li>
 * </ul>
 */
public enum NotificationType {
    /** A board was shared with the recipient (a new share was created). */
    BOARD_SHARED,
    /** The recipient's role on a board changed. */
    ROLE_CHANGED,
    /** The recipient's access to a board was revoked. */
    ACCESS_REVOKED
}
