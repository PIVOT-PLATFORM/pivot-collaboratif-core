package fr.pivot.collaboratif.exception;

/**
 * Thrown when an invitation targets an e-mail that resolves to no active user of the inviting
 * caller's tenant (US08.2.5). Mapped to HTTP 404 — deliberately indistinguishable from an unknown
 * board/share, so an e-mail from another tenant cannot be enumerated.
 */
public class InviteeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public InviteeNotFoundException() {
        super("No user matches the invited e-mail in this tenant");
    }
}
