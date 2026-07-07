package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when a board share token does not exist, belongs to a different board,
 * or has already been revoked.
 *
 * <p>Using a single exception for all three cases prevents information disclosure
 * (callers cannot distinguish "token doesn't exist" from "token already revoked").
 */
public class BoardShareTokenNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given token identifier.
     *
     * @param tokenId the UUID of the token that could not be found or was already revoked
     */
    public BoardShareTokenNotFoundException(final UUID tokenId) {
        super("Share token not found: " + tokenId);
    }
}
