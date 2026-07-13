package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.List;

/**
 * Board-state reply sent to {@code /user/queue/board-state}, targeted at a single joining
 * session only (via {@code convertAndSendToUser}), when that session sends {@code JOIN}
 * (EN08.4).
 *
 * <p>Resolves the "Ambiguïté ouverte" previously documented in {@code CanvasEventRepository}:
 * a late-joining participant is now brought up to date on the board's current card state
 * directly (not via full event replay) — {@code cards} reflects the durable {@code Card}
 * table, which is exactly the state a client needs to render the canvas as it currently stands.
 *
 * @param boardId the board UUID as a string
 * @param cards   every card currently on the board, ordered by layer then creation time
 */
public record BoardStatePayload(String boardId, List<CardDto> cards) {
}
