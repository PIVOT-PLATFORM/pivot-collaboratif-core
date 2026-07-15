package fr.pivot.collaboratif.whiteboard.canvas.dto;

/**
 * Server-to-client message broadcast to {@code /topic/whiteboard/{boardId}} for every
 * accepted canvas action.
 *
 * <p>The {@code type} field identifies the action ({@code card:created}, {@code card:deleted},
 * {@code board:cursors}...). The {@code data} field carries the type-specific payload the
 * frontend ({@code board.store.ts}) reads as {@code envelope.data}.
 *
 * <p><strong>{@code data} is deliberately {@link Object}, not {@code Map<String, Object>}.</strong>
 * The frontend's per-type handlers expect different JSON shapes at the top level of {@code data}:
 * a flat object for {@code card:created}/{@code connection:created}/{@code card:updated}
 * ({@code this.on<Card>(...)}), a bare string for {@code card:deleted}/{@code connection:deleted}/
 * {@code cards:ungrouped} ({@code this.on<string>(...)}), and an array for {@code board:cursors}/
 * {@code board:presence} ({@code this.on<T[]>(...)}). Modelling {@code data} as {@link Object}
 * lets every handler broadcast exactly the shape its frontend consumer subscribes to — mirroring
 * the inbound {@link CanvasActionMessage#data()}, which is {@link Object} for the same reason.
 *
 * @param type    the outgoing wire type (see {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType#wireOut()})
 * @param boardId the board UUID as a string (allows clients to route without parsing)
 * @param userId  the emitting user's {@code public.users.id} as a string
 * @param data    type-specific payload — a JSON object, a bare string, or an array; the exact
 *                shape depends on {@code type}
 */
public record BroadcastCanvasMessage(
        String type,
        String boardId,
        String userId,
        Object data) {
}
