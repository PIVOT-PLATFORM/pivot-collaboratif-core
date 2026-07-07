package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when the caller is a board member but lacks the required role for a requested operation.
 *
 * <p>Mapped to HTTP 403 Forbidden by {@link GlobalExceptionHandler}.
 */
public class BoardAccessDeniedException extends RuntimeException {

    /**
     * Creates an access-denied exception for the given board.
     *
     * @param boardId the UUID of the board for which access was denied
     */
    public BoardAccessDeniedException(final UUID boardId) {
        super("Access denied to board: " + boardId);
    }
}
