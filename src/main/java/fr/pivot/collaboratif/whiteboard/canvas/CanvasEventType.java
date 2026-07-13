package fr.pivot.collaboratif.whiteboard.canvas;

/**
 * Whitelisted STOMP message types for whiteboard canvas actions (US08.3.1, extended EN08.4).
 *
 * <p>Any incoming message whose {@code type} field does not match one of these values
 * is rejected by {@link WhiteboardActionController} with a WARN log and silently dropped —
 * the session is not closed.
 *
 * <p>Persistence behaviour by type:
 * <ul>
 *   <li>{@link #DRAW} — broadcast only in this Socle (EN08.4); free-hand strokes are persisted
 *       as a {@link Card} of {@link CardType#DRAW} via {@link #CARD_CREATE}/{@link #CARD_UPDATE},
 *       not as a {@code canvas_event} row (the append-only {@code DRAW} persistence of US08.3.1
 *       is superseded by the {@link Card} current-state table — see EN08.4's Gate 1 notes).</li>
 *   <li>{@link #CARD_CREATE}, {@link #CARD_MOVE}, {@link #CARD_RESIZE}, {@link #CARD_UPDATE},
 *       {@link #CARD_RECOLOR}, {@link #CARD_DELETE}, {@link #CARD_LAYER} — mutate the durable
 *       {@link Card} table (EN08.4).</li>
 *   <li>{@link #JOIN}, {@link #LEAVE}, {@link #CURSOR_MOVE}, {@link #UNDO} — ephemeral,
 *       broadcast only, never persisted.</li>
 * </ul>
 */
public enum CanvasEventType {
    /** User joins the canvas room; server assigns colour, emits PARTICIPANTS_UPDATE, and
     * replies to the joining session alone with the board's current {@link Card} list. */
    JOIN,
    /** User leaves the canvas room; server removes metadata and emits PARTICIPANTS_UPDATE. */
    LEAVE,
    /** Drawing action broadcast (visual feedback during a stroke); not persisted directly —
     * see the class-level Javadoc for how free-hand strokes are actually persisted (EN08.4). */
    DRAW,
    /** Cursor position update; broadcast only, never persisted (high-frequency, low-value). */
    CURSOR_MOVE,
    /** Undo request; broadcast for visual sync, stack logic delegated to US08.3.3. */
    UNDO,
    /** Creates a new {@link Card} (EN08.4). */
    CARD_CREATE,
    /** Moves an existing {@link Card}; refused if locked (EN08.4). */
    CARD_MOVE,
    /** Resizes an existing {@link Card}; refused if locked (EN08.4). */
    CARD_RESIZE,
    /** Updates an existing {@link Card}'s content; refused if locked (EN08.4). */
    CARD_UPDATE,
    /** Recolors an existing {@link Card}; refused if locked (EN08.4). */
    CARD_RECOLOR,
    /** Deletes an existing {@link Card}; not blocked by {@code locked} in this Socle (EN08.4). */
    CARD_DELETE,
    /** Changes an existing {@link Card}'s Z-order layer; not blocked by {@code locked} (EN08.4). */
    CARD_LAYER
}
