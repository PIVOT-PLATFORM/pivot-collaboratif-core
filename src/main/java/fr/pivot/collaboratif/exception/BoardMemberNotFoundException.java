package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when a board membership record cannot be found.
 *
 * <p>Maps to HTTP 404 Not Found via {@link GlobalExceptionHandler}.
 */
public class BoardMemberNotFoundException extends RuntimeException {

    /**
     * Creates the exception for a specific board-user combination.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     */
    public BoardMemberNotFoundException(final UUID boardId, final Long userId) {
        super("Member " + userId + " not found on board " + boardId);
    }
}
