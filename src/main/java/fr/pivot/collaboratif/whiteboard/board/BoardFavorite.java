package fr.pivot.collaboratif.whiteboard.board;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity representing a user's personal "favorite" marker on a whiteboard board
 * (US08.1.6).
 *
 * <p>The primary key is a composite of {@code boardId} and {@code userId}: a row's mere
 * existence means the board is favorited by that user. Favorites are strictly personal —
 * never shared or visible to other members of the same board.
 */
@Entity
@Table(name = "board_favorite", schema = "collaboratif")
public class BoardFavorite {

    /** Composite primary key: (boardId, userId). */
    @EmbeddedId
    private BoardFavoriteId id;

    /** Timestamp when the favorite was created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-arg constructor required by JPA. */
    protected BoardFavorite() {
    }

    /**
     * Creates a new favorite marker with an explicit creation timestamp.
     *
     * @param id        the composite primary key
     * @param createdAt the instant the favorite was created
     */
    public BoardFavorite(final BoardFavoriteId id, final Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    /**
     * Sets {@code createdAt} to the current instant before the first insert
     * if it has not already been set.
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /**
     * Returns the composite primary key.
     *
     * @return the {@link BoardFavoriteId}
     */
    public BoardFavoriteId getId() {
        return id;
    }

    /**
     * Returns the timestamp when the favorite was created.
     *
     * @return the createdAt instant
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
