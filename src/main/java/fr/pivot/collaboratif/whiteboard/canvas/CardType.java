package fr.pivot.collaboratif.whiteboard.canvas;

/**
 * Typed discriminant for a persisted whiteboard {@link Card} (EN08.4).
 *
 * <p>Replaces the untyped {@code DRAW} sub-type strings previously carried in the opaque
 * {@code canvas_event.payload} JSONB (US08.3.1) with a first-class, queryable column.
 *
 * <p>An incoming {@code type} value that does not match one of these constants is never
 * rejected with an error — it falls back to {@link #TEXT} (see {@link CanvasActionService}),
 * mirroring the reference whiteboard's tolerant behaviour for a malformed/omitted type.
 */
public enum CardType {
    /** Free-form text note ("sticky"). */
    TEXT,
    /** Uploaded or pasted image. */
    IMAGE,
    /** URL card with an OpenGraph preview (title/description/image), enriched asynchronously. */
    LINK,
    /** Vector shape (rectangle, ellipse, etc.). */
    SHAPE,
    /** Free-hand drawing (stroke path). */
    DRAW,
    /** Small persistent text label, visually distinct from a full {@link #TEXT} note. */
    LABEL,
    /** Grid/table of cells. */
    TABLE
}
