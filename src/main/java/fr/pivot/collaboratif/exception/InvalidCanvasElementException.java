package fr.pivot.collaboratif.exception;

/**
 * Thrown when a canvas element payload (today: whiteboard template seed content) violates
 * the strict JSON schema whitelist enforced by {@code CanvasElementValidator}
 * (shape/text/image, closed field set, bounded values).
 *
 * <p>Because the only caller-supplied input on the template initialization path is
 * {@code templateId} — never the element content itself, which comes exclusively from
 * Flyway-seeded rows — this exception signals an internal invariant violation (seed data
 * drifted from the schema), not a client input error. It is mapped to HTTP 500 by
 * {@code GlobalExceptionHandler}, with the underlying detail logged server-side only.
 */
public class InvalidCanvasElementException extends RuntimeException {

    /**
     * Creates an invalid-canvas-element exception with a diagnostic message.
     *
     * @param message description of the schema violation, for server-side logging only
     */
    public InvalidCanvasElementException(final String message) {
        super(message);
    }
}
