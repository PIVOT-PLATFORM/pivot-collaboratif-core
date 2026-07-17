package fr.pivot.collaboratif.whiteboard.importer.dto;

import java.util.List;

/**
 * Response body for {@code POST /whiteboard/boards/{boardId}/import/klaxoon} (US08.13.1, HTTP
 * 201): the counts of created objects plus the three id lists.
 *
 * <p>The id lists are the <strong>source of truth for undo</strong> — there is no server-side
 * import-history table. The client is expected to hold onto {@code cardIds}/{@code connectionIds}/
 * {@code frameIds} and replay them verbatim as the body of a subsequent
 * {@code POST .../import/undo} call.
 *
 * @param cards         the number of cards created
 * @param connections   the number of connectors created (after orphan filtering — connectors
 *                      whose endpoints did not both resolve through the {@code idMap} are not
 *                      counted here)
 * @param frames        the number of frames created
 * @param cardIds       the created cards' server ids, as strings, in insertion order
 * @param connectionIds the created connectors' server ids, as strings, in insertion order
 * @param frameIds      the created frames' server ids, as strings, in insertion order
 */
public record ImportKlaxoonResponse(
        int cards,
        int connections,
        int frames,
        List<String> cardIds,
        List<String> connectionIds,
        List<String> frameIds) {
}
