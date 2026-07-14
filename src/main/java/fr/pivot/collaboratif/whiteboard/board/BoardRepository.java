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
     * Finds all non-trashed boards accessible by a user within a tenant, optionally filtered
     * by a case/accent-insensitive substring match on title or description (US08.1.8).
     *
     * <p>A board is considered accessible if the user is either the owner or
     * an active member. Results are ordered by {@code updatedAt} descending. Soft-deleted
     * boards ({@code deletedAt IS NOT NULL}) are always excluded (US08.1.7).
     *
     * @param userId   the {@code public.users.id} of the user whose accessible boards to find
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param search   normalized (lower-cased, accent-stripped) search text, or {@code null}/
     *                 blank to disable filtering
     * @param pageable pagination and sorting parameters
     * @return a page of boards accessible to the specified user
     */
    @Query(value = """
            SELECT DISTINCT b FROM Board b
            LEFT JOIN BoardMember bm ON bm.id.boardId = b.id AND bm.id.userId = :userId
            WHERE b.tenantId = :tenantId
              AND b.deletedAt IS NULL
              AND (b.ownerId = :userId OR bm.id.userId = :userId)
              AND (:search IS NULL OR :search = ''
                   OR LOWER(FUNCTION('unaccent', b.title)) LIKE CONCAT('%', :search, '%')
                   OR LOWER(FUNCTION('unaccent', COALESCE(b.description, ''))) LIKE CONCAT('%', :search, '%'))
            """,
            countQuery = """
            SELECT COUNT(DISTINCT b.id) FROM Board b
            LEFT JOIN BoardMember bm ON bm.id.boardId = b.id AND bm.id.userId = :userId
            WHERE b.tenantId = :tenantId
              AND b.deletedAt IS NULL
              AND (b.ownerId = :userId OR bm.id.userId = :userId)
              AND (:search IS NULL OR :search = ''
                   OR LOWER(FUNCTION('unaccent', b.title)) LIKE CONCAT('%', :search, '%')
                   OR LOWER(FUNCTION('unaccent', COALESCE(b.description, ''))) LIKE CONCAT('%', :search, '%'))
            """)
    Page<Board> findAccessibleByUser(
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Finds all trashed boards owned by a user within a tenant (US08.1.7).
     *
     * <p>Only boards where the caller is the OWNER are returned — the trash is not shared
     * with other members.
     *
     * @param ownerId  the owner's {@code public.users.id}
     * @param tenantId the {@code public.tenants.id} to restrict results to
     * @param pageable pagination and sorting parameters
     * @return a page of trashed boards owned by the specified user
     */
    Page<Board> findByOwnerIdAndTenantIdAndDeletedAtIsNotNull(
            Long ownerId, Long tenantId, Pageable pageable);

    /**
     * Finds a non-trashed board by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the board does not exist, belongs
     * to a different tenant, or is soft-deleted, preventing cross-tenant information
     * disclosure and hiding trashed boards from normal access (US08.1.7).
     *
     * @param id       the board UUID
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the board, or empty if not found
     */
    Optional<Board> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, Long tenantId);

    /**
     * Finds a board by its identifier and tenant regardless of trash status.
     *
     * <p>Used by operations that must act on a board while it is in the trash (restore,
     * permanent delete) — see {@link #findByIdAndTenantIdAndDeletedAtIsNull} for the
     * normal-access variant that hides trashed boards.
     *
     * @param id       the board UUID
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the board (trashed or not), or empty if not found
     */
    Optional<Board> findByIdAndTenantId(UUID id, Long tenantId);
}
