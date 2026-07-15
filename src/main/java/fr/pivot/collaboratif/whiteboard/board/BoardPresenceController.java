package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.context.RequestPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller exposing aggregated whiteboard board presence (US08.1.9, parity §2.2).
 *
 * <p>Deliberately a separate controller from {@link BoardController} — both map under
 * {@code /whiteboard/boards}, but this one owns a single, narrowly-scoped endpoint
 * ({@code GET /whiteboard/boards/presence}) so it can evolve independently of the board
 * CRUD/settings surface. Spring resolves the literal {@code /presence} path ahead of {@link
 * BoardController#findById}'s {@code {boardId}} variable segment regardless of registration
 * order (exact-match patterns are always more specific than a path-variable pattern), so the
 * two controllers coexist without any route-ordering concern.
 *
 * <p>Requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * RequestPrincipal} the same way as every other endpoint in this module (EN08.3) — tenant and
 * user identity always come from the resolved principal, never from the request.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/boards/presence}.
 */
@RestController
@RequestMapping("/whiteboard/boards")
public class BoardPresenceController {

    private final BoardPresenceService boardPresenceService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param boardPresenceService the presence aggregation service
     */
    public BoardPresenceController(final BoardPresenceService boardPresenceService) {
        this.boardPresenceService = boardPresenceService;
    }

    /**
     * Returns the number of participants currently connected to each of the caller's accessible
     * boards, keyed by board id.
     *
     * <p>Only exposes a count — never a participant identity (userId, name, avatar) — and only
     * for boards the caller may access (no cross-board or cross-tenant presence leak). Always
     * returns HTTP 200, including an empty object when the realtime presence subsystem is
     * unavailable.
     *
     * @param principal the resolved caller identity (user + tenant)
     * @return a map of board id (string) to connected-participant count
     */
    @GetMapping("/presence")
    public Map<String, Integer> presence(final RequestPrincipal principal) {
        return boardPresenceService.presenceForUser(principal.userId(), principal.tenantId());
    }
}
