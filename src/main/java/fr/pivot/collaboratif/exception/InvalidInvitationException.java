package fr.pivot.collaboratif.exception;

/**
 * Thrown when an invitation is syntactically valid but not allowed for a business reason
 * (US08.2.5): inviting oneself, or inviting the board creator. Mapped to HTTP 400 with a
 * machine-readable {@code code} so the frontend can announce a precise, localized error.
 */
public class InvalidInvitationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable error code ({@code SELF_INVITE} or {@code ALREADY_OWNER}). */
    private final String code;

    /**
     * Creates the exception.
     *
     * @param code    the machine-readable error code
     * @param message the human-readable French detail
     */
    public InvalidInvitationException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /** @return the machine-readable error code */
    public String getCode() {
        return code;
    }
}
