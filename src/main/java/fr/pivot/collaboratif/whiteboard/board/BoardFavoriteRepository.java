package fr.pivot.collaboratif.whiteboard.board;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link BoardFavorite} entities (US08.1.6).
 */
public interface BoardFavoriteRepository extends JpaRepository<BoardFavorite, BoardFavoriteId> {

    /**
     * Checks whether a board is favorited by a given user.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     * @return {@code true} if a favorite marker exists for this board/user pair
     */
    boolean existsByIdBoardIdAndIdUserId(UUID boardId, Long userId);

    /**
     * Deletes the favorite marker for a board/user pair, if it exists.
     *
     * <p>A no-op (0 rows affected) when no such favorite exists — the caller (service layer)
     * treats this as an idempotent success per the API contract.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     */
    @Modifying
    void deleteByIdBoardIdAndIdUserId(UUID boardId, Long userId);

    /**
     * Returns the set of board ids among {@code boardIds} that are favorited by the given
     * user — used to enrich a page of {@code BoardResponse} without an N+1 query per row.
     *
     * @param userId   the user's {@code public.users.id}
     * @param boardIds the candidate board UUIDs (typically the ids of a single page)
     * @return the subset of {@code boardIds} favorited by the user
     */
    @Query("SELECT f.id.boardId FROM BoardFavorite f "
            + "WHERE f.id.userId = :userId AND f.id.boardId IN :boardIds")
    List<UUID> findFavoritedBoardIds(
            @Param("userId") Long userId, @Param("boardIds") Set<UUID> boardIds);
}
