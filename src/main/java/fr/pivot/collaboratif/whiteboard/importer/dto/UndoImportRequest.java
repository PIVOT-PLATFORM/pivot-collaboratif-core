package fr.pivot.collaboratif.whiteboard.importer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /whiteboard/boards/{boardId}/import/undo} (US08.13.1) — the three
 * id lists a prior {@code POST .../import/klaxoon} call returned, replayed verbatim by the client
 * to undo that import. There is no server-side import-history table: the client-supplied lists
 * are the only record of what an import created.
 *
 * <p>Every id is deleted only if it both exists <strong>and</strong> belongs to {@code boardId}
 * (the path parameter) — an id belonging to another board is silently skipped, never deleted
 * (strict IDOR guard; the response's counts reflect only what was actually removed).
 *
 * @param cardIds       the card ids to delete (server-assigned UUID strings), capped at 10 000
 * @param connectionIds the connector ids to delete (server-assigned UUID strings), capped at
 *                      10 000 — may legitimately overlap with connectors already removed by the
 *                      {@code ON DELETE CASCADE} of a deleted card's endpoint
 * @param frameIds      the frame ids to delete (server-assigned UUID strings), capped at 1 000
 */
public record UndoImportRequest(
        @NotNull @Size(max = 10_000) List<@NotBlank String> cardIds,
        @NotNull @Size(max = 10_000) List<@NotBlank String> connectionIds,
        @NotNull @Size(max = 1_000) List<@NotBlank String> frameIds) {
}
