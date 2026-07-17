package fr.pivot.collaboratif.whiteboard.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * Finds a share row by its board-independent surrogate {@code shareId} <em>and</em> the board
     * it must belong to (US08.2.5). Scoping the lookup by {@code boardId} — not by {@code shareId}
     * alone — is the IDOR guard §6.1: a manager of board A can never resolve (and therefore never
     * mutate) a share row of board B even by knowing its {@code shareId}.
     *
     * @param shareId the surrogate share identifier from the {@code /shares/{shareId}} path
     * @param boardId the board UUID the share must belong to
     * @return an {@link Optional} containing the share row, or empty if it does not belong to the
     *         board (treated as 404 by the caller, anti-enumeration convention)
     */
    Optional<BoardMember> findByShareIdAndIdBoardId(UUID shareId, UUID boardId);

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

    /**
     * Batch variant of {@link #findByIdBoardIdAndIdUserId} for a whole page of boards: returns the
     * caller's membership row on each of {@code boardIds} they belong to, in one query — used to
     * resolve every caller role for a board listing without an N+1 query per row. Boards the caller
     * only owns (no membership row beyond the OWNER seed) still return their row; boards with no
     * matching membership are simply absent from the result.
     *
     * @param boardIds the candidate board UUIDs (typically the ids of a single page)
     * @param userId   the caller's {@code public.users.id}
     * @return the caller's membership rows among {@code boardIds}
     */
    List<BoardMember> findAllByIdBoardIdInAndIdUserId(Set<UUID> boardIds, Long userId);

    /**
     * Batch variant of {@link #countByIdBoardIdAndRoleNot} for a whole page of boards: returns the
     * active-share count (members holding a role other than {@code excludedRole}) grouped per board,
     * in one query — used to enrich a board listing without an N+1 count per row. Boards with zero
     * shares are absent from the result (callers must default them to {@code 0}).
     *
     * @param boardIds     the candidate board UUIDs (typically the ids of a single page)
     * @param excludedRole the role to exclude from the count (the owner's role)
     * @return one {@link BoardShareCount} per board that has at least one active share
     */
    @Query("SELECT m.id.boardId AS boardId, COUNT(m) AS shareCount FROM BoardMember m "
            + "WHERE m.id.boardId IN :boardIds AND m.role <> :excludedRole GROUP BY m.id.boardId")
    List<BoardShareCount> countSharesGroupedByBoard(
            @Param("boardIds") Set<UUID> boardIds, @Param("excludedRole") BoardRole excludedRole);

    /** Projection for {@link #countSharesGroupedByBoard}: a board id and its active-share count. */
    interface BoardShareCount {
        /** @return the board UUID */
        UUID getBoardId();

        /** @return the number of active shares (members other than the owner) on the board */
        long getShareCount();
    }
}
