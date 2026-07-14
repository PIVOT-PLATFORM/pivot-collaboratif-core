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
     * @param userId  the user's {@code public.users.id}
     * @return an {@link Optional} containing the membership, or empty if not found
     */
    Optional<BoardMember> findByIdBoardIdAndIdUserId(UUID boardId, Long userId);

    /**
     * Returns all membership records for the given board, ordered by join date ascending.
     *
     * @param boardId the board UUID
     * @return list of all members (includes the OWNER entry)
     */
    java.util.List<BoardMember> findAllByIdBoardIdOrderByJoinedAtAsc(UUID boardId);

    /**
     * Counts active shares on a board — every membership row except the given role (US08.1.9,
     * parity §2.2, "shareCount = nombre de partages actifs, membres hors owner"). The owner's
     * own membership row (always present, see {@code BoardService#create}) is excluded by
     * passing {@link BoardRole#OWNER}.
     *
     * @param boardId      the board UUID
     * @param excludedRole the role to exclude from the count (the owner's role)
     * @return the number of members on this board holding a role other than {@code excludedRole}
     */
    long countByIdBoardIdAndRoleNot(UUID boardId, BoardRole excludedRole);
}
