package fr.pivot.collaboratif.exception;

/**
 * Thrown when a share token has expired or its maximum usage count has been reached.
 *
 * <p>Maps to HTTP 410 Gone — the token existed but is no longer usable.
 */
public class BoardShareTokenExpiredException extends RuntimeException {

    /**
     * Creates the exception with a descriptive message.
     *
     * @param reason a brief description (expired vs. quota exceeded)
     */
    public BoardShareTokenExpiredException(final String reason) {
        super(reason);
    }
}
