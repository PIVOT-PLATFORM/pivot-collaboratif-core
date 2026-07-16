package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Frame} entities (EN08, Frames).
 *
 * <p>Every mutating query below scopes explicitly by both {@code id} <strong>and</strong>
 * {@code boardId} — never {@code id} alone — so that a frame id belonging to a different board
 * (guessed or leaked cross-tenant) can never be mutated through a forged request, mirroring
 * {@link CardRepository}'s defence-in-depth convention. Each returns the number of affected rows
 * so the caller ({@link CanvasActionService}) can silently skip the broadcast when the frame was
 * missing or on a different board (both collapse to {@code 0} rows affected).
 */
public interface FrameRepository extends JpaRepository<Frame, UUID> {

    /**
     * Returns every frame on the given board, ordered by layer then creation time, for the
     * {@code board:state} reply sent to a client on {@code JOIN}.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @return the board's frames; empty if none exist
     */
    List<Frame> findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(UUID boardId, Long tenantId);

    /**
     * Returns a frame scoped by board ownership, for re-reading it after a mutation to broadcast
     * the full updated {@link fr.pivot.collaboratif.whiteboard.canvas.dto.FrameDto}. Scoping by
     * {@code (id, boardId)} keeps a leaked or guessed cross-board id inert.
     *
     * @param id      the frame UUID
     * @param boardId the owning board UUID
     * @return the frame if it exists and belongs to this board; empty otherwise
     */
    Optional<Frame> findByIdAndBoardId(UUID id, UUID boardId);

    /**
     * Moves a frame, guarded by board ownership in the query.
     *
     * @param id      the frame UUID
     * @param boardId the owning board UUID (defence in depth against a cross-board id)
     * @param posX    the new X position
     * @param posY    the new Y position
     * @return the number of rows affected (0 if not found or on a different board)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Frame f SET f.posX = :posX, f.posY = :posY "
            + "WHERE f.id = :id AND f.boardId = :boardId")
    int move(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("posX") double posX,
            @Param("posY") double posY);

    /**
     * Resizes a frame (width/height only), guarded by board ownership in the query. Position is
     * updated by a separate {@link #move} call when the frontend sends {@code posX}/{@code posY}
     * alongside a resize.
     *
     * @param id      the frame UUID
     * @param boardId the owning board UUID
     * @param width   the new width
     * @param height  the new height
     * @return the number of rows affected (0 if not found or on a different board)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Frame f SET f.width = :width, f.height = :height "
            + "WHERE f.id = :id AND f.boardId = :boardId")
    int resize(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("width") double width,
            @Param("height") double height);

    /**
     * Changes a frame's Z-order layer, guarded by board ownership in the query.
     *
     * @param id      the frame UUID
     * @param boardId the owning board UUID
     * @param layer   the new layer
     * @return the number of rows affected (0 if not found or on a different board)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Frame f SET f.layer = :layer WHERE f.id = :id AND f.boardId = :boardId")
    int updateLayer(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("layer") int layer);

    /**
     * Deletes a frame scoped by board ownership. Idempotent: deleting an id that does not exist
     * (already deleted, on a different board, or never existed) simply returns {@code 0} — never
     * an exception.
     *
     * @param id      the frame UUID
     * @param boardId the owning board UUID
     * @return the number of rows deleted (0 or 1)
     */
    long deleteByIdAndBoardId(UUID id, UUID boardId);
}
