package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CardConnection} entities (US08.7.1).
 *
 * <p>Every mutating query scopes explicitly by both {@code id} <strong>and</strong>
 * {@code boardId} — never {@code id} alone — consistent with {@link CardRepository}'s
 * defence-in-depth convention against a cross-board id (guessed or leaked cross-tenant).
 */
public interface CardConnectionRepository extends JpaRepository<CardConnection, UUID> {

    /**
     * Returns every connector on the given board, for the board-state reply sent to a client
     * on {@code JOIN}.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @return the board's connectors; empty if none exist
     */
    List<CardConnection> findAllByBoardIdAndTenantId(UUID boardId, Long tenantId);

    /**
     * Returns whether a connector already exists between the given pair of cards on this
     * board, in either direction — the bidirectional anti-duplicate check (parity spec §3.6):
     * a connector {@code (fromId, toId)} is considered a duplicate of one already stored as
     * either {@code (fromId, toId)} or {@code (toId, fromId)}.
     *
     * @param boardId the owning board UUID
     * @param fromId  one endpoint card id
     * @param toId    the other endpoint card id
     * @return {@code true} if a connector already links this pair in either direction
     */
    @Query("SELECT COUNT(c) > 0 FROM CardConnection c WHERE c.boardId = :boardId "
            + "AND ((c.fromId = :fromId AND c.toId = :toId) OR (c.fromId = :toId AND c.toId = :fromId))")
    boolean existsBetween(
            @Param("boardId") UUID boardId,
            @Param("fromId") UUID fromId,
            @Param("toId") UUID toId);

    /**
     * Returns a connector scoped by board ownership, for a partial style patch
     * ({@code connection:update}, US08.7.2). Scoping by {@code (id, boardId)} — never {@code id}
     * alone — means an id belonging to another board (guessed, or leaked cross-tenant) never
     * resolves here, so the caller can treat "not found" and "found on another board" identically
     * without leaking which case occurred.
     *
     * @param id      the connector UUID
     * @param boardId the owning board UUID
     * @return the connector if it exists and belongs to this board; empty otherwise
     */
    Optional<CardConnection> findByIdAndBoardId(UUID id, UUID boardId);

    /**
     * Deletes a connector scoped by board ownership. Idempotent: deleting an id that does not
     * exist (already deleted — including via the {@code ON DELETE CASCADE} triggered by an
     * endpoint card's own deletion — or never existed) simply returns {@code 0}, never an
     * exception.
     *
     * @param id      the connector UUID
     * @param boardId the owning board UUID
     * @return the number of rows deleted (0 or 1)
     */
    long deleteByIdAndBoardId(UUID id, UUID boardId);

    /**
     * Deletes every connector of a board, scoped by tenant (defence-in-depth cross-tenant
     * guard). Backs the destructive {@code board:reset} STOMP action
     * ({@link CanvasActionService#handleBoardReset}); run before the card deletion so no card
     * still referenced by a connector is removed out from under it.
     *
     * @param boardId  the owning board UUID
     * @param tenantId the owning tenant's {@code public.tenants.id}
     * @return the number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM CardConnection c WHERE c.boardId = :boardId AND c.tenantId = :tenantId")
    long deleteAllByBoardIdAndTenantId(@Param("boardId") UUID boardId, @Param("tenantId") Long tenantId);

    /**
     * Deletes every connector in {@code ids} that belongs to {@code boardId}, in a single bulk
     * statement — the Klaxoon import undo (US08.13.1, {@code POST .../import/undo}). Scoped
     * strictly by {@code boardId}, mirroring {@link CardRepository#deleteAllByIdInAndBoardId}.
     * Naturally idempotent against the {@code ON DELETE CASCADE} triggered by an endpoint card's
     * own deletion: a connector id already gone that way simply does not contribute to the
     * returned count — the caller (undo) never sees an error for it (acceptance criterion: "le
     * deleteMany explicite sur connectionIds peut compter 0 sans erreur").
     *
     * @param ids     the candidate connector ids to delete
     * @param boardId the owning board UUID — the only board these ids may be deleted from
     * @return the number of rows actually deleted (0..ids.size())
     */
    @Modifying
    @Query("DELETE FROM CardConnection c WHERE c.id IN :ids AND c.boardId = :boardId")
    int deleteAllByIdInAndBoardId(@Param("ids") Collection<UUID> ids, @Param("boardId") UUID boardId);
}
