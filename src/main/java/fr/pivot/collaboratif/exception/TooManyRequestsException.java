package fr.pivot.collaboratif.exception;

/**
 * Thrown when a client exceeds the rate limit for a protected endpoint.
 *
 * <p>Maps to HTTP 429 Too Many Requests.
 */
public class TooManyRequestsException extends RuntimeException {

    /**
     * Creates the exception with a descriptive message.
     *
     * @param message explanation of which limit was exceeded
     */
    public TooManyRequestsException(final String message) {
        super(message);
    }
}
