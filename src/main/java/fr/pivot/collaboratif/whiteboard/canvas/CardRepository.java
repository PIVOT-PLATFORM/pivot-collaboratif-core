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
     * Counts how many of the given card ids exist and belong to the given board — used by
     * {@link CanvasActionService#handleConnectionCreate} to validate, in a single query, that
     * both a connector's endpoints are real cards of this board before insert (US08.7.1,
     * correctif §6.5: unlike the reference whiteboard, which lets Prisma throw an uncaught FK
     * error on a missing endpoint, this repo validates existence first and refuses silently).
     *
     * @param ids     the candidate card ids (typically exactly two: a connector's fromId/toId)
     * @param boardId the owning board UUID (defence in depth against a cross-board id)
     * @return the number of {@code ids} that exist and belong to {@code boardId} (0..ids.size())
     */
    long countByIdInAndBoardId(Collection<UUID> ids, UUID boardId);

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
     * <p>{@code clearAutomatically = true}: {@code handleCardUpdate} re-reads the card via
     * {@code findById} right after this bulk update to broadcast the full card DTO. A bulk
     * JPQL {@code UPDATE} bypasses the first-level cache by design, so without clearing it, any
     * managed {@code Card} instance already sitting in the persistence context from an earlier
     * read in the same transaction (e.g. a prior type lookup) would make the subsequent
     * {@code findById} return that pre-update, now-stale instance instead of hitting the
     * database again — reproduced as a real bug (US08.6.3 IT failures) when a full-entity
     * lookup preceded this query; kept defensively even though the current callers only use
     * the lighter {@link #findTypeByIdAndBoardId} projection beforehand.
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID
     * @param content the new content
     * @return the number of rows affected (0 if not found, on a different board, or locked)
     */
    @Modifying(clearAutomatically = true)
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
     * Returns a card's type scoped by board, without loading the full entity — used to dispatch
     * type-specific content validation ({@link ShapeStyleSanitizer}, {@link
     * ImageCardContentValidator}) before a {@code CARD_UPDATE} write. A card's {@code type} is
     * immutable after creation, so this read carries no race window against the atomic
     * {@link #updateContentIfUnlocked} write that follows it.
     *
     * @param id      the card UUID
     * @param boardId the owning board UUID
     * @return the card's type, empty if not found or on a different board
     */
    @Query("SELECT c.type FROM Card c WHERE c.id = :id AND c.boardId = :boardId")
    Optional<CardType> findTypeByIdAndBoardId(@Param("id") UUID id, @Param("boardId") UUID boardId);

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
     * Locks or unlocks every card in {@code ids} that belongs to {@code boardId}, in a single
     * bulk update (card:lock, parity spec §4.x). Scoping by {@code boardId} keeps a leaked or
     * guessed cross-board id inert. Not guarded by the current {@code locked} value — locking is
     * idempotent and unlocking must always be able to reach an already-locked card.
     *
     * @param ids     the card ids to (un)lock
     * @param boardId the owning board UUID
     * @param locked  the new locked state
     * @return the number of rows affected
     */
    @Modifying
    @Query("UPDATE Card c SET c.locked = :locked, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id IN :ids AND c.boardId = :boardId")
    int lockCards(
            @Param("ids") Collection<UUID> ids,
            @Param("boardId") UUID boardId,
            @Param("locked") boolean locked);

    /**
     * Assigns {@code groupId} to every card in {@code ids} that belongs to {@code boardId}, in a
     * single bulk update (cards:group). The {@code groupId} is generated server-side by
     * {@link CanvasActionService#handleCardsGroup} — never client-supplied.
     *
     * @param ids     the card ids to group
     * @param boardId the owning board UUID
     * @param groupId the server-assigned group UUID
     * @return the number of rows affected
     */
    @Modifying
    @Query("UPDATE Card c SET c.groupId = :groupId, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id IN :ids AND c.boardId = :boardId")
    int groupCards(
            @Param("ids") Collection<UUID> ids,
            @Param("boardId") UUID boardId,
            @Param("groupId") UUID groupId);

    /**
     * Clears the group assignment ({@code group_id} and {@code group_color}) of every card of
     * {@code boardId} currently in {@code groupId} (cards:ungroup). Scoped by {@code boardId} so a
     * cross-board group id cannot dissolve another board's group.
     *
     * @param groupId the group UUID to dissolve
     * @param boardId the owning board UUID
     * @return the number of rows affected
     */
    @Modifying
    @Query("UPDATE Card c SET c.groupId = null, c.groupColor = null, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.groupId = :groupId AND c.boardId = :boardId")
    int ungroupByGroupId(@Param("groupId") UUID groupId, @Param("boardId") UUID boardId);

    /**
     * Recolors the outline ({@code group_color}) of every card of {@code boardId} in
     * {@code groupId} (cards:group-color).
     *
     * @param groupId    the group UUID to recolor
     * @param boardId    the owning board UUID
     * @param groupColor the new group outline hex colour
     * @return the number of rows affected
     */
    @Modifying
    @Query("UPDATE Card c SET c.groupColor = :groupColor, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.groupId = :groupId AND c.boardId = :boardId")
    int recolorGroup(
            @Param("groupId") UUID groupId,
            @Param("boardId") UUID boardId,
            @Param("groupColor") String groupColor);

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

    /**
     * Deletes every card of a board, scoped by tenant (defence-in-depth cross-tenant guard).
     * Backs the destructive {@code board:reset} STOMP action
     * ({@link CanvasActionService#handleBoardReset}) — the OWNER-only, atomic "clear the whole
     * board" operation mirroring the reference whiteboard's reset.
     *
     * @param boardId  the owning board UUID
     * @param tenantId the owning tenant's {@code public.tenants.id}
     * @return the number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM Card c WHERE c.boardId = :boardId AND c.tenantId = :tenantId")
    long deleteAllByBoardIdAndTenantId(@Param("boardId") UUID boardId, @Param("tenantId") Long tenantId);
}
