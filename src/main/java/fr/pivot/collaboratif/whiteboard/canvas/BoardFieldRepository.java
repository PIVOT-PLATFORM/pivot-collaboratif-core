package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link BoardField} entities (US08.10.1).
 *
 * <p>Every mutating query below scopes explicitly by both {@code id} <strong>and</strong>
 * {@code boardId} — never {@code id} alone — so that a field id belonging to a different board
 * (guessed or leaked cross-tenant) can never be mutated through a forged request, mirroring
 * {@link FrameRepository}/{@link CardRepository}'s defence-in-depth convention. The update returns
 * the number of affected rows and the delete a row count, so the caller
 * ({@link CanvasActionService}) can silently skip the broadcast when the field was missing or on a
 * different board (both collapse to {@code 0}).
 */
public interface BoardFieldRepository extends JpaRepository<BoardField, UUID> {

    /**
     * Returns every field of the given board, ordered by display order then creation time, for the
     * {@code fields} entry of the {@code board:state} reply sent to a client on {@code JOIN}.
     *
     * @param boardId the board UUID
     * @return the board's fields; empty if none exist
     */
    List<BoardField> findAllByBoardIdOrderByOrderAscCreatedAtAsc(UUID boardId);

    /**
     * Returns a field scoped by board ownership. Scoping by {@code (id, boardId)} keeps a leaked or
     * guessed cross-board id inert.
     *
     * @param id      the field UUID
     * @param boardId the owning board UUID
     * @return the field if it exists and belongs to this board; empty otherwise
     */
    Optional<BoardField> findByIdAndBoardId(UUID id, UUID boardId);

    /**
     * Updates a field's {@code name}/{@code emoji}/{@code options}, guarded by board ownership in
     * the query. The {@code type} is deliberately never updated (fixed for the field's lifetime,
     * acceptance criterion).
     *
     * @param id      the field UUID
     * @param boardId the owning board UUID (defence in depth against a cross-board id)
     * @param name    the new name
     * @param emoji   the new emoji, or {@code null}
     * @param options the new options JSON string, or {@code null}
     * @return the number of rows affected (0 if not found or on a different board)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BoardField f SET f.name = :name, f.emoji = :emoji, f.options = :options "
            + "WHERE f.id = :id AND f.boardId = :boardId")
    int updateNameEmojiOptions(
            @Param("id") UUID id,
            @Param("boardId") UUID boardId,
            @Param("name") String name,
            @Param("emoji") String emoji,
            @Param("options") String options);

    /**
     * Deletes a field scoped by board ownership. Idempotent: deleting an id that does not exist
     * (already deleted, on a different board, or never existed) simply returns {@code 0} — never an
     * exception. The database FK cascade removes the field's {@link CardFieldValue} rows.
     *
     * @param id      the field UUID
     * @param boardId the owning board UUID
     * @return the number of rows deleted (0 or 1)
     */
    long deleteByIdAndBoardId(UUID id, UUID boardId);
}
