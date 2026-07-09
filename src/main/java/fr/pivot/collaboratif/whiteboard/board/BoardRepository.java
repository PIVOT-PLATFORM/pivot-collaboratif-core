package fr.pivot.collaboratif.whiteboard.board;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Board} entities.
 *
 * <p>Provides standard CRUD operations through {@link JpaRepository} plus
 * custom query methods for tenant-scoped board retrieval.
 */
public interface BoardRepository extends JpaRepository<Board, UUID> {

    /**
     * Finds all boards accessible by a user within a tenant.
     *
     * <p>A board is considered accessible if the user is either the owner or
     * an active member. Results are ordered by {@code updatedAt} descending.
     *
     * @param userId   the {@code public.users.id} of the user whose accessible boards to find
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param pageable pagination and sorting parameters
     * @return a page of boards accessible to the specified user
     */
    @Query(value = """
            SELECT DISTINCT b FROM Board b
            LEFT JOIN BoardMember bm ON bm.id.boardId = b.id AND bm.id.userId = :userId
            WHERE b.tenantId = :tenantId
              AND (b.ownerId = :userId OR bm.id.userId = :userId)
            """,
            countQuery = """
            SELECT COUNT(DISTINCT b.id) FROM Board b
            LEFT JOIN BoardMember bm ON bm.id.boardId = b.id AND bm.id.userId = :userId
            WHERE b.tenantId = :tenantId
              AND (b.ownerId = :userId OR bm.id.userId = :userId)
            """)
    Page<Board> findAccessibleByUser(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            Pageable pageable);

    /**
     * Finds a board by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the board does not exist or belongs
     * to a different tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the board UUID
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the board, or empty if not found
     */
    Optional<Board> findByIdAndTenantId(UUID id, Long tenantId);
}
