package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Card} entities (EN08.4).
 *
 * <p>Every mutating query below scopes explicitly by both {@code id} <strong>and</strong>
 * {@code boardId} — never {@code id} alone — so that a card id belonging to a different board
 * (guessed or leaked cross-tenant) can never be mutated through a forged request. Move/resize/
 * update/recolor additionally guard on {@code locked = false} directly in the query itself
 * (rather than a separate read-then-write check), returning the number of affected rows so the
 * caller can silently skip the broadcast when the card was locked, already deleted, or on a
 * different board (all three cases collapse to {@code 0} rows affected, indistinguishable to the
 * caller by design — see {@link CanvasActionService}).
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    /**
     * Returns every card on the given board, ordered by layer then creation time, for the
     * board-state reply sent to a client on {@code JOIN}.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @return the board's cards; empty if none exist
     */
    List<Card> findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(UUID boardId, Long tenantId);

    /**
     * Moves a card, guarded by lock state and board ownership in the same query.
     *
     * @param id    the card UUID
     * @param boardId the owning board UUID (defence in depth against a cross-board id)
     * @param posX  the new X position
     * @param posY  the new Y position
     * @return the number of rows affected (0 if not found, on a different board, or locked)
     */
    @Modifying
    @Query("UPDATE Card c SET c.posX = :posX, c.posY = :posY, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.boardId = :boardId AND c.locked = false")
    int moveIfUnlocked(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("posX") double posX,
            @Param("posY") double posY);

    /**
     * Resizes a card, guarded by lock state and board ownership in the same query.
     *
     * @param id     the card UUID
     * @param boardId the owning board UUID
     * @param width  the new width
     * @param height the new height
     * @return the number of rows affected (0 if not found, on a different board, or locked)
     */
    @Modifying
    @Query("UPDATE Card c SET c.width = :width, c.height = :height, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.boardId = :boardId AND c.locked = false")
    int resizeIfUnlocked(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("width") double width,
            @Param("height") double height);

    /**
     * Updates a card's content, guarded by lock state and board ownership in the same query.
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID
     * @param content the new content
     * @return the number of rows affected (0 if not found, on a different board, or locked)
     */
    @Modifying
    @Query("UPDATE Card c SET c.content = :content, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.boardId = :boardId AND c.locked = false")
    int updateContentIfUnlocked(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("content") String content);

    /**
     * Updates a card's OpenGraph metadata cache (US08.6.5), guarded by board ownership but
     * <strong>not</strong> by {@code locked} — enrichment is a system-triggered background
     * refresh of the preview cache, not a user-authored mutation, so a locked card's preview
     * still updates. Returns the affected row count so the caller ({@code
     * OpenGraphEnrichmentListener}) can silently skip the {@code card:meta_updated} broadcast if
     * the card was deleted (or moved to a different board) before the asynchronous fetch
     * completed.
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID (defence in depth against a cross-board id)
     * @param meta    the new JSON metadata cache ({@code {title, description, image, siteName}}),
     *                or {@code null} to clear it
     * @return the number of rows affected (0 if not found or on a different board)
     */
    @Modifying
    @Query("UPDATE Card c SET c.meta = :meta, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.boardId = :boardId")
    int updateMeta(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("meta") String meta);

    /**
     * Recolors a card, guarded by lock state and board ownership in the same query.
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID
     * @param color   the new hex colour
     * @return the number of rows affected (0 if not found, on a different board, or locked)
     */
    @Modifying
    @Query("UPDATE Card c SET c.color = :color, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.boardId = :boardId AND c.locked = false")
    int recolorIfUnlocked(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("color") String color);

    /**
     * Changes a card's Z-order layer. Deliberately <strong>not</strong> guarded by lock state —
     * layer changes are not blocked by {@code locked} (parity spec §4.6: layer is the sole
     * mutation locking does not protect against).
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID
     * @param layer   the new layer
     * @return the number of rows affected (0 if not found or on a different board)
     */
    @Modifying
    @Query("UPDATE Card c SET c.layer = :layer, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.boardId = :boardId")
    int updateLayer(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("layer") int layer);

    /**
     * Deletes a card scoped by board ownership. Not guarded by lock state <strong>at the query
     * level</strong> — there is no row left to condition an {@code UPDATE}-style {@code WHERE
     * locked = false} on once it is gone — the caller ({@link CanvasActionService#handleCardDelete})
     * performs an explicit {@code locked} read beforehand and skips this call entirely when the
     * card is locked (fix/EN08.4, parity with the six Sprint 12 card-type US). Idempotent:
     * deleting an id that does not exist (already deleted, or never existed) simply returns
     * {@code 0} — never an exception.
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID
     * @return the number of rows deleted (0 or 1)
     */
    long deleteByIdAndBoardId(UUID id, UUID boardId);
}
