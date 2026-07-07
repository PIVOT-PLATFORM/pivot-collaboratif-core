package fr.pivot.collaboratif.whiteboard.board;

/**
 * Role of a user on a whiteboard board.
 *
 * <p>Roles determine what operations a member may perform:
 * <ul>
 *   <li>{@link #OWNER} — full control, including deletion and member management</li>
 *   <li>{@link #EDITOR} — can modify board content</li>
 *   <li>{@link #VIEWER} — read-only access</li>
 * </ul>
 */
public enum BoardRole {
    /** Full control over the board. Assigned to the creator at board creation. */
    OWNER,
    /** Can modify board content but cannot manage members or delete the board. */
    EDITOR,
    /** Read-only access to the board. */
    VIEWER
}
