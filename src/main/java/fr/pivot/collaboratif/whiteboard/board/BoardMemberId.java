package fr.pivot.collaboratif.whiteboard.board;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link BoardMember}.
 *
 * <p>Combines {@code boardId} and {@code userId} to form a unique membership identifier.
 * Implements {@link Serializable} as required by JPA for embeddable primary keys.
 */
@Embeddable
public class BoardMemberId implements Serializable {

    /** The board part of the composite key. */
    private UUID boardId;

    /** The user part of the composite key. */
    private UUID userId;

    /** No-arg constructor required by JPA. */
    protected BoardMemberId() {
    }

    /**
     * Creates a composite key for the given board and user.
     *
     * @param boardId the board UUID
     * @param userId  the user UUID
     */
    public BoardMemberId(final UUID boardId, final UUID userId) {
        this.boardId = boardId;
        this.userId = userId;
    }

    /**
     * Returns the board UUID.
     *
     * @return the boardId
     */
    public UUID getBoardId() {
        return boardId;
    }

    /**
     * Returns the user UUID.
     *
     * @return the userId
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two {@link BoardMemberId} instances are equal when both their {@code boardId}
     * and {@code userId} fields are equal.
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BoardMemberId that)) {
            return false;
        }
        return Objects.equals(boardId, that.boardId)
                && Objects.equals(userId, that.userId);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(boardId, userId);
    }
}
