package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when a restore or permanent-delete operation is attempted on a board that is not
 * currently in the trash (i.e. {@code deleted_at} is {@code null}).
 *
 * <p>Mapped to HTTP 409 Conflict by {@link GlobalExceptionHandler} (US08.1.7).
 */
public class BoardNotInTrashException extends RuntimeException {

    /**
     * Creates a conflict exception for the given board.
     *
     * @param boardId the UUID of the board that is not in the trash
     */
    public BoardNotInTrashException(final UUID boardId) {
        super("Board is not in the trash: " + boardId);
    }
}
