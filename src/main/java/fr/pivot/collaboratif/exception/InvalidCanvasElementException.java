package fr.pivot.collaboratif.exception;

/**
 * Thrown when a canvas element payload (today: whiteboard template seed content) violates
 * the strict JSON schema whitelist enforced by {@code CanvasElementValidator}
 * (shape/text/image, closed field set, bounded values).
 *
 * <p>The only caller-supplied input on the template <em>initialization</em> path
 * ({@code WhiteboardTemplateService#initializeBoard}) is {@code templateId} — never the
 * element content itself. That method only ever resolves the 3 Flyway-seeded global
 * templates ({@code WhiteboardTemplateService#resolveGlobalTemplate}, {@code tenant_id IS
 * NULL}), so a failure there signals an internal invariant violation (seed data drifted from
 * the schema), not a client input error, and is mapped to HTTP 500 by
 * {@code GlobalExceptionHandler} with the underlying detail logged server-side only. Note that
 * tenant-owned templates created via "save as template"
 * ({@code WhiteboardTemplateService#createFromBoard}) store unvalidated content and are not
 * currently reachable by {@code initializeBoard} — a future endpoint resolving them through
 * this validator must not assume a 500 remains the correct response for caller-influenced
 * content.
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
