package fr.pivot.collaboratif.whiteboard.canvas;

import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardConnectionDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CardDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.FrameDto;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ImportUndoneBroadcastPayload;
import fr.pivot.collaboratif.whiteboard.canvas.dto.ImportedBroadcastPayload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared STOMP broadcaster for board-level lifecycle events that originate outside the
 * canvas action pipeline ({@link CanvasActionService}) — currently only the board reset
 * (US08.2.4).
 *
 * <p>Reuses the same destination convention and payload shape ({@link BroadcastCanvasMessage})
 * as {@link CanvasActionService#handle}, so existing STOMP clients subscribed to
 * {@code /topic/whiteboard/{boardId}} handle this message the same way they already handle
 * JOIN/LEAVE/DRAW/CURSOR_MOVE/UNDO.
 */
@Service
public class WhiteboardBroadcastService {

    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the service.
     *
     * @param messagingTemplate STOMP broadcast template
     */
    public WhiteboardBroadcastService(final SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Broadcasts a board-reset notification to every participant currently subscribed to the
     * board's canvas topic (US08.2.4).
     *
     * <p>The reset itself (deleting the board's persisted DRAW events) has already happened
     * server-side by the time this is called — this only notifies connected clients so they
     * clear their local canvas view in real time.
     *
     * <p>Wire type is {@code "board:resetted"} — the frontend ({@code board.store.ts}, the
     * PouetPouet-mirroring wire vocabulary, see EN08.4 recette finding on
     * pivot-collaboratif-core#68) listens for that exact past-tense name, not
     * {@link CanvasEventType#RESET}'s bare Java enum name. Sending the wrong name meant the
     * REST reset call itself always succeeded, but every already-connected client (other than
     * the one who triggered it) never saw the board actually clear in real time — this
     * notification was silently dropped client-side.
     *
     * @param boardId the board UUID
     * @param userId  the {@code public.users.id} of the user who triggered the reset
     */
    public void broadcastReset(final UUID boardId, final Long userId) {
        String destination = BOARD_TOPIC_PREFIX + boardId;
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                "board:resetted", boardId.toString(), userId.toString(), Map.of());
        messagingTemplate.convertAndSend(destination, msg);
    }

    /**
     * Broadcasts a successful Klaxoon import (US08.13.1) to every participant currently
     * subscribed to the board's canvas topic, carrying the <strong>full created objects</strong>
     * (their existing wire DTOs) rather than bare ids — so an already-connected client can render
     * the imported content immediately without a follow-up fetch.
     *
     * <p>Wire type is {@code "board:imported"}. Sent on {@code /topic/whiteboard/{boardId}} — the
     * real, currently-wired STOMP topic for this board (the US text references
     * {@code /topic/board/{boardId}}, which does not exist in this codebase; see
     * {@link #BOARD_TOPIC_PREFIX} and {@link #broadcastReset}, already established for
     * {@code board:resetted}).
     *
     * @param boardId     the board UUID
     * @param userId      the {@code public.users.id} of the user who triggered the import
     * @param cards       the cards created by the import
     * @param connections the connectors created by the import (orphan-filtered — endpoints not
     *                    both present in the import's {@code idMap} were already dropped)
     * @param frames      the frames created by the import
     */
    public void broadcastImported(
            final UUID boardId,
            final Long userId,
            final List<CardDto> cards,
            final List<CardConnectionDto> connections,
            final List<FrameDto> frames) {
        String destination = BOARD_TOPIC_PREFIX + boardId;
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                "board:imported", boardId.toString(), userId.toString(),
                new ImportedBroadcastPayload(cards, connections, frames));
        messagingTemplate.convertAndSend(destination, msg);
    }

    /**
     * Broadcasts a successful import undo (US08.13.1) to every participant currently subscribed
     * to the board's canvas topic, carrying the <strong>requested</strong> id lists — not the
     * effectively-deleted ones (acceptance criterion: a client-supplied id already gone via
     * cascade, or belonging to another board and therefore never deleted, is still echoed back
     * as "requested" so the client can reliably clear its own local state for every id it asked
     * to remove).
     *
     * <p>Wire type is {@code "board:import-undone"}. Sent on {@code /topic/whiteboard/{boardId}}.
     *
     * @param boardId       the board UUID
     * @param userId        the {@code public.users.id} of the user who triggered the undo
     * @param cardIds       the requested card ids (as supplied by the client), not the deleted ones
     * @param connectionIds the requested connector ids (as supplied by the client)
     * @param frameIds      the requested frame ids (as supplied by the client)
     */
    public void broadcastImportUndone(
            final UUID boardId,
            final Long userId,
            final List<String> cardIds,
            final List<String> connectionIds,
            final List<String> frameIds) {
        String destination = BOARD_TOPIC_PREFIX + boardId;
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                "board:import-undone", boardId.toString(), userId.toString(),
                new ImportUndoneBroadcastPayload(cardIds, connectionIds, frameIds));
        messagingTemplate.convertAndSend(destination, msg);
    }
}
