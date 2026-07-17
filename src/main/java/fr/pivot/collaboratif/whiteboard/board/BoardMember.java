package fr.pivot.collaboratif.whiteboard.board;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a user's membership and role on a whiteboard board.
 *
 * <p>The primary key is a composite of {@code boardId} and {@code userId},
 * ensuring each user appears at most once per board. The {@code joinedAt}
 * timestamp is set automatically on first persist.
 *
 * <p>This entity is the concrete model behind the {@code BoardShare} concept of US08.2.5 (no
 * separate table): in addition to the composite key it carries a board-independent surrogate
 * {@code shareId}, the stable target of the {@code /shares/{shareId}} governance routes. The
 * surrogate lets those routes scope every read/write by {@code (shareId, boardId)}, closing the
 * IDOR flaw §6.1 (a manager of board A altering a share row of board B by knowing its line id).
 */
@Entity
@Table(name = "board_member", schema = "collaboratif")
public class BoardMember {

    /** Composite primary key: (boardId, userId). */
    @EmbeddedId
    private BoardMemberId id;

    /**
     * Board-independent surrogate identifier of this share row (US08.2.5).
     *
     * <p>Exposed as {@code shareId} through the {@code /shares/{shareId}} API and never reused
     * across boards, so a share row is always addressable independently of the {@code userId}
     * while remaining scoped to its board.
     */
    @Column(name = "share_id", nullable = false, unique = true, updatable = false)
    private UUID shareId;

    /** Role of the user on this board. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private BoardRole role;

    /** Timestamp when the user joined the board. */
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /** No-arg constructor required by JPA. */
    protected BoardMember() {
    }

    /**
     * Creates a new board membership with an explicit join timestamp.
     *
     * @param id       the composite primary key
     * @param role     the user's role on the board
     * @param joinedAt the instant the user joined
     */
    public BoardMember(final BoardMemberId id, final BoardRole role, final Instant joinedAt) {
        this.id = id;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    /**
     * Sets {@code joinedAt} to the current instant, and generates the surrogate
     * {@code shareId} when absent, before the first insert.
     */
    @PrePersist
    public void prePersist() {
        if (this.joinedAt == null) {
            this.joinedAt = Instant.now();
        }
        if (this.shareId == null) {
            this.shareId = UUID.randomUUID();
        }
    }

    /**
     * Returns the composite primary key.
     *
     * @return the {@link BoardMemberId}
     */
    public BoardMemberId getId() {
        return id;
    }

    /**
     * Returns the board-independent surrogate identifier of this share row (US08.2.5).
     *
     * @return the {@code shareId}, or {@code null} before the first persist
     */
    public UUID getShareId() {
        return shareId;
    }

    /**
     * Returns the user's role on the board.
     *
     * @return the {@link BoardRole}
     */
    public BoardRole getRole() {
        return role;
    }

    /**
     * Updates the user's role on the board.
     *
     * @param role the new role to assign
     */
    public void setRole(final BoardRole role) {
        this.role = role;
    }

    /**
     * Returns the timestamp when the user joined the board.
     *
     * @return the joinedAt instant
     */
    public Instant getJoinedAt() {
        return joinedAt;
    }
}
