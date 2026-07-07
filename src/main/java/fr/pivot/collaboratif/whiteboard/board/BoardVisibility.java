package fr.pivot.collaboratif.whiteboard.board;

/**
 * Visibility level of a whiteboard board.
 *
 * <p>All boards start as {@link #PRIVATE}. Future versions may introduce additional
 * visibility modes (e.g. team-scoped or link-sharing).
 */
public enum BoardVisibility {
    /** Only the owner and explicitly added members can see and access this board. */
    PRIVATE,
    /** Any user within the same tenant can discover and access this board. */
    PUBLIC
}
