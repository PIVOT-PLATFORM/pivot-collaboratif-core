package fr.pivot.collaboratif.whiteboard.canvas.dto;

/**
 * Server-to-client message broadcast to {@code /topic/whiteboard/{boardId}} for every
 * accepted canvas action.
 *
 * <p>The {@code type} field identifies the action ({@code JOIN}, {@code LEAVE},
 * {@code DRAW}, {@code CURSOR_MOVE}, {@code frame:created}...). The {@code data} field carries
 * the type-specific payload enriched by the server.
 *
 * <p><strong>{@code data} is {@link Object}, not a {@code Map}</strong> — most broadcasts are a
 * JSON object (a flat card/frame, or {@code {id, layer}}), but some are a <strong>bare
 * string</strong> ({@code frame:deleted} carries the id string directly, matching the
 * frontend's {@code this.on<string>('frame:deleted', …)}). A handler can therefore broadcast
 * exactly the shape its frontend consumer subscribes to.
 *
 * @param type    the canvas event type
 * @param boardId the board UUID as a string (allows clients to route without parsing)
 * @param userId  the emitting user's {@code public.users.id} as a string
 * @param data    type-specific payload (object or bare string); depends on {@code type}
 */
public record BroadcastCanvasMessage(
        String type,
        String boardId,
        String userId,
        Object data) {
}
