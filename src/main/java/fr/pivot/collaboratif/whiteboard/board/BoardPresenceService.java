package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.whiteboard.canvas.ParticipantMetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic backing {@code GET /whiteboard/boards/presence} (US08.1.9, parity §2.2, line
 * 312 — equivalent of the reference whiteboard's {@code io.in('board:{id}').fetchSockets()}).
 *
 * <p>For every board the caller may access, returns the number of participants currently
 * connected to that board's realtime room, read from {@link ParticipantMetaStore} — the same
 * Redis-backed store populated by {@code CanvasActionService} on {@code board:join}/{@code
 * board:leave}. Because that store is a Redis HASH keyed by {@code userId} (one entry per
 * user, overwritten on every JOIN regardless of how many tabs/sessions that user has open), a
 * count read from it is <strong>already deduplicated by {@code userId}</strong> — a single user
 * connected through several sockets/tabs on the same board counts once.
 *
 * <p>Boards with zero connected participants are omitted from the returned map (a sparse map,
 * not one entry per accessible board) — callers default an absent {@code boardId} to a count of
 * zero, matching the reference behaviour of only ever reporting non-empty rooms.
 *
 * <p>If the realtime presence subsystem is unavailable (e.g. Redis unreachable), the whole
 * computation is abandoned and an empty map is returned rather than a partial one or an error
 * — {@code GET /whiteboard/boards/presence} always answers 200, per the US's explicit AC.
 */
@Service
@Transactional(readOnly = true)
public class BoardPresenceService {

    private static final Logger LOG = LoggerFactory.getLogger(BoardPresenceService.class);

    private final BoardRepository boardRepository;
    private final ParticipantMetaStore participantMetaStore;

    /**
     * Creates the service.
     *
     * @param boardRepository      repository resolving the caller's accessible board ids
     * @param participantMetaStore Redis-backed store of currently-connected participants
     */
    public BoardPresenceService(
            final BoardRepository boardRepository,
            final ParticipantMetaStore participantMetaStore) {
        this.boardRepository = boardRepository;
        this.participantMetaStore = participantMetaStore;
    }

    /**
     * Computes the connected-participant count for every board accessible to the caller.
     *
     * @param userId   calling user's {@code public.users.id}
     * @param tenantId calling tenant's {@code public.tenants.id}
     * @return a map of board id (as string) to connected-participant count, containing only
     *         boards with at least one connected participant; an empty map if the realtime
     *         presence subsystem is unavailable
     */
    public Map<String, Integer> presenceForUser(final Long userId, final Long tenantId) {
        try {
            List<UUID> boardIds = boardRepository.findAccessibleBoardIds(userId, tenantId);
            Map<String, Integer> result = new LinkedHashMap<>();
            for (UUID boardId : boardIds) {
                int count = participantMetaStore.getAll(tenantId, boardId).size();
                if (count > 0) {
                    result.put(boardId.toString(), count);
                }
            }
            return result;
        } catch (RuntimeException e) {
            LOG.warn("Presence subsystem unavailable — returning empty presence map: {}",
                    e.getMessage());
            return Map.of();
        }
    }
}
