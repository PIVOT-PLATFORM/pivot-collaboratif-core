package fr.pivot.collaboratif.exception;

/**
 * Thrown when {@code enabledActivities} in a board settings update contains a code that is
 * not part of the known activity whitelist ({@code BoardActivity}).
 *
 * <p>Mapped to HTTP 400 Bad Request with {@code { "code": "INVALID_ACTIVITY" } } by
 * {@link GlobalExceptionHandler} (US08.2.4).
 */
public class InvalidActivityException extends RuntimeException {

    /**
     * Creates an invalid-activity exception for the given unknown activity code.
     *
     * @param activityCode the unrecognized activity code supplied by the caller
     */
    public InvalidActivityException(final String activityCode) {
        super("Unknown activity code: " + activityCode);
    }
}
