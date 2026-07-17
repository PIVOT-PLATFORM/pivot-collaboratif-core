package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.List;

/**
 * {@code data} payload of the {@code board:import-undone} STOMP broadcast (US08.13.1) sent on
 * {@code /topic/whiteboard/{boardId}} after a successful import undo — carries the
 * <strong>requested</strong> id lists (as supplied by the client), not the effectively-deleted
 * ones, so every connected client can reliably clear its own local state for every id it asked to
 * remove, even one already gone via cascade or belonging to another board.
 *
 * @param cardIds       the requested card ids
 * @param connectionIds the requested connector ids
 * @param frameIds      the requested frame ids
 */
public record ImportUndoneBroadcastPayload(
        List<String> cardIds,
        List<String> connectionIds,
        List<String> frameIds) {
}
