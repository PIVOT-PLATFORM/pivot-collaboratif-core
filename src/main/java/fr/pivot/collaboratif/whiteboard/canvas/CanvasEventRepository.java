package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CanvasEvent} entities.
 *
 * <p>Provides standard CRUD operations plus a board history query used to replay
 * past {@link CanvasEventType#DRAW} events for a late-joining participant.
 */
public interface CanvasEventRepository extends JpaRepository<CanvasEvent, UUID> {

    /**
     * Returns all canvas events for the given board, ordered chronologically.
     *
     * <p>Used to restore the canvas state for a participant joining an active board session.
     * Only {@link CanvasEventType#DRAW} events are currently persisted; other types are
     * excluded automatically by the service layer.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant UUID (for tenant isolation)
     * @return ordered list of canvas events; empty if none exist
     */
    List<CanvasEvent> findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(UUID boardId, UUID tenantId);
}
