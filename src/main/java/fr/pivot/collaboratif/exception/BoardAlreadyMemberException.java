package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when a user attempts to join a board they are already a member of.
 *
 * <p>Maps to HTTP 409 Conflict.
 */
public class BoardAlreadyMemberException extends RuntimeException {

    /**
     * Creates the exception for the given board and user.
     *
     * @param boardId the board UUID the user already belongs to
     * @param userId  the user UUID that is already a member
     */
    public BoardAlreadyMemberException(final UUID boardId, final UUID userId) {
        super("User " + userId + " is already a member of board " + boardId);
    }
}
