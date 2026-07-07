package fr.pivot.collaboratif.whiteboard.canvas;

/**
 * Whitelisted STOMP message types for whiteboard canvas actions (US08.3.1).
 *
 * <p>Any incoming message whose {@code type} field does not match one of these values
 * is rejected by {@link WhiteboardActionController} with a WARN log and silently dropped —
 * the session is not closed.
 *
 * <p>Persistence behaviour by type:
 * <ul>
 *   <li>{@link #DRAW} — persisted in {@code collaboratif.canvas_event} (Last-Write-Wins).</li>
 *   <li>{@link #JOIN}, {@link #LEAVE}, {@link #CURSOR_MOVE}, {@link #UNDO} — ephemeral,
 *       broadcast only.</li>
 * </ul>
 */
public enum CanvasEventType {
    /** User joins the canvas room; server assigns colour and emits PARTICIPANTS_UPDATE. */
    JOIN,
    /** User leaves the canvas room; server removes metadata and emits PARTICIPANTS_UPDATE. */
    LEAVE,
    /** Drawing action (stroke / shape / erase / move / resize / text); persisted in DB. */
    DRAW,
    /** Cursor position update; broadcast only, never persisted (high-frequency, low-value). */
    CURSOR_MOVE,
    /** Undo request; broadcast for visual sync, stack logic delegated to US08.3.3. */
    UNDO
}
