package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CanvasEvent} entities.
 *
 * <p>Provides standard CRUD operations plus a board history query currently used only by tests
 * and reserved for a future replay-on-join feature (see below).
 */
public interface CanvasEventRepository extends JpaRepository<CanvasEvent, UUID> {

    /**
     * Returns all canvas events for the given board, ordered chronologically.
     *
     * <p><strong>Not currently called from any production code path.</strong> US08.3.1 persists
     * {@link CanvasEventType#DRAW} events for every board so that a full history exists in
     * storage, but this US explicitly leaves open — see "Ambiguïté ouverte" in
     * {@code us-connexion-ws-canvas.md} — whether or how a late-joining participant is brought
     * up to date on the existing canvas state (full event replay vs. periodic snapshot vs.
     * something else). That decision has not been made, so no replay-on-join logic has been
     * wired up in the service layer, and this query is not invoked when a client joins a board.
     * It is reserved for whichever future US implements replay-on-join, once that architectural
     * question is resolved by the Architect Agent — do not assume it is already exercised in
     * production, and do not build replay behavior on top of it without that resolution.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (for tenant isolation)
     * @return ordered list of canvas events; empty if none exist
     */
    List<CanvasEvent> findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(UUID boardId, Long tenantId);
}
