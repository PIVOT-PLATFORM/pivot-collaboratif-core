package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.Map;

/**
 * Server-to-client message broadcast to {@code /topic/whiteboard/{boardId}} for every
 * accepted canvas action.
 *
 * <p>The {@code type} field identifies the action ({@code JOIN}, {@code LEAVE},
 * {@code DRAW}, {@code CURSOR_MOVE}, {@code UNDO}). The {@code data} map carries
 * type-specific fields enriched by the server (e.g. the colour assigned at JOIN).
 *
 * @param type    the canvas event type
 * @param boardId the board UUID as a string (allows clients to route without parsing)
 * @param userId  the emitting user's {@code public.users.id} as a string
 * @param data    type-specific payload; the exact fields depend on {@code type}
 */
public record BroadcastCanvasMessage(
        String type,
        String boardId,
        String userId,
        Map<String, Object> data) {
}
