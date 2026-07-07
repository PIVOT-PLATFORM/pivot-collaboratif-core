package fr.pivot.collaboratif.whiteboard.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link BoardMember} entities.
 *
 * <p>Provides standard CRUD operations through {@link JpaRepository} plus
 * convenience query methods for membership lookups.
 */
public interface BoardMemberRepository extends JpaRepository<BoardMember, BoardMemberId> {

    /**
     * Finds the membership record for a specific board-user combination.
     *
     * @param boardId the board UUID
     * @param userId  the user UUID
     * @return an {@link Optional} containing the membership, or empty if not found
     */
    Optional<BoardMember> findByIdBoardIdAndIdUserId(UUID boardId, UUID userId);

    /**
     * Returns all membership records for the given board, ordered by join date ascending.
     *
     * @param boardId the board UUID
     * @return list of all members (includes the OWNER entry)
     */
    java.util.List<BoardMember> findAllByIdBoardIdOrderByJoinedAtAsc(UUID boardId);
}
