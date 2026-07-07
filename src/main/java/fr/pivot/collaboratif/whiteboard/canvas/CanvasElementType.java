package fr.pivot.collaboratif.whiteboard.canvas;

/**
 * Whitelisted kinds of drawable canvas element content (US08.4.1).
 *
 * <p>Distinct from {@link CanvasEventType}, which enumerates STOMP message envelope
 * types (JOIN/LEAVE/DRAW/CURSOR_MOVE/UNDO). This enum instead classifies the
 * <em>content</em> carried by a persisted drawable element — today used for whiteboard
 * template seed content (validated by {@link CanvasElementValidator} at board
 * initialization time). Only these three kinds are accepted; any other value is
 * rejected.
 */
public enum CanvasElementType {
    /** A vector shape (rectangle, ellipse, line, diamond). */
    SHAPE,
    /** A text label. */
    TEXT,
    /** A static image reference (internal, whitelisted asset path only in the Socle). */
    IMAGE
}
