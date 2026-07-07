package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when a board does not exist, belongs to another tenant, or the caller is not a member.
 *
 * <p>Using a single exception for all three cases prevents cross-tenant information disclosure
 * (the caller cannot distinguish "board doesn't exist" from "board exists but you can't see it").
 */
public class BoardNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given board identifier.
     *
     * @param boardId the UUID of the board that could not be found or accessed
     */
    public BoardNotFoundException(final UUID boardId) {
        super("Board not found: " + boardId);
    }
}
