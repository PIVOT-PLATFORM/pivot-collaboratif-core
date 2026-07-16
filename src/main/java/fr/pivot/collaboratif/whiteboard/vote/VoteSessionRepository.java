package fr.pivot.collaboratif.whiteboard.vote;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link VoteSession} entities (Vote / dot-voting feature).
 *
 * <p>Every lookup that precedes a mutation scopes explicitly by {@code (id, boardId, tenantId)} —
 * never {@code id} alone — so a session id belonging to another board or tenant (guessed or
 * leaked) never resolves here, consistent with the rest of this module's anti-IDOR convention.
 */
public interface VoteSessionRepository extends JpaRepository<VoteSession, UUID> {

    /**
     * Returns the current {@link VoteStatus#ACTIVE} session for a board within a tenant, if any.
     * At most one such session exists (see the partial unique index in {@code V4__vote.sql}).
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @param status   the status to filter on (always {@link VoteStatus#ACTIVE} at the call site)
     * @return the active session, or empty if none is active
     */
    Optional<VoteSession> findByBoardIdAndTenantIdAndStatus(UUID boardId, Long tenantId, VoteStatus status);

    /**
     * Returns whether an {@link VoteStatus#ACTIVE} session already exists for the given board —
     * the application-level single-active-session guard used before starting a new one.
     *
     * @param boardId the board UUID
     * @param status  the status to test (always {@link VoteStatus#ACTIVE} at the call site)
     * @return {@code true} if a session with that status exists on the board
     */
    boolean existsByBoardIdAndStatus(UUID boardId, VoteStatus status);

    /**
     * Returns the most recently created session of the given status for a board within a tenant —
     * used by {@code GET .../vote/last} to expose the last {@link VoteStatus#CLOSED} session.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @param status   the status to filter on
     * @return the newest matching session, or empty if none exists
     */
    Optional<VoteSession> findFirstByBoardIdAndTenantIdAndStatusOrderByCreatedAtDesc(
            UUID boardId, Long tenantId, VoteStatus status);

    /**
     * Returns a session scoped by board and tenant, acquiring a pessimistic write lock on the row
     * for the duration of the surrounding transaction. This serialises concurrent
     * {@code vote:cast}/{@code vote:uncast} operations on the same session, making the
     * count-then-insert quota check atomic and preventing oversurvote — a Serializable-equivalent
     * guarantee scoped to the single session row (see {@code V4__vote.sql}).
     *
     * @param id       the session UUID
     * @param boardId  the owning board UUID
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the locked session if it exists and belongs to this board/tenant; empty otherwise
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM VoteSession s WHERE s.id = :id AND s.boardId = :boardId AND s.tenantId = :tenantId")
    Optional<VoteSession> findForUpdate(
            @Param("id") UUID id, @Param("boardId") UUID boardId, @Param("tenantId") Long tenantId);
}
