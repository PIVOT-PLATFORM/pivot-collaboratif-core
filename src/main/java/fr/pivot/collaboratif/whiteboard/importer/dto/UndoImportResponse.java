package fr.pivot.collaboratif.whiteboard.importer.dto;

/**
 * Response body for {@code POST /whiteboard/boards/{boardId}/import/undo} (US08.13.1, HTTP 200 —
 * deliberately not 204, the acceptance criterion requires the actually-deleted counts in the
 * body): the number of rows each category's bulk delete actually removed.
 *
 * <p>A count can legitimately be lower than the requested list's size — either because an id
 * belonged to another board (silently skipped, strict IDOR guard) or, for {@code connections},
 * because the connector was already removed by the {@code ON DELETE CASCADE} of one of its
 * endpoint cards being deleted in the same transaction. Neither case is an error.
 *
 * @param cards       the number of cards actually deleted
 * @param connections the number of connectors actually deleted
 * @param frames      the number of frames actually deleted
 */
public record UndoImportResponse(int cards, int connections, int frames) {
}
