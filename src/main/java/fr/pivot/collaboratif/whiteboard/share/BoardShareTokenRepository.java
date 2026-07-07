package fr.pivot.collaboratif.whiteboard.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link BoardShareToken} entities.
 */
public interface BoardShareTokenRepository extends JpaRepository<BoardShareToken, UUID> {

    /**
     * Finds an active (non-revoked) token by its identifier and associated board.
     *
     * @param id      the token UUID
     * @param boardId the board UUID — prevents revocation of tokens from other boards
     * @return the token if found and not revoked
     */
    @Query("SELECT t FROM BoardShareToken t WHERE t.id = :id AND t.boardId = :boardId"
            + " AND t.revokedAt IS NULL")
    Optional<BoardShareToken> findActiveByIdAndBoardId(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId);
}
