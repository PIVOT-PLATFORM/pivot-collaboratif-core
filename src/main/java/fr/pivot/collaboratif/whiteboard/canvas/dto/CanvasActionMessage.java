package fr.pivot.collaboratif.whiteboard.canvas.dto;

/**
 * Incoming STOMP canvas action sent by a client on
 * {@code /app/whiteboard/{boardId}/action}.
 *
 * <p>The {@code type} field is a raw string resolved against the
 * {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType} whitelist
 * ({@link fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType#fromWire}) by
 * {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasActionService}. Keeping {@code type}
 * as a plain {@code String} allows a structured WARN log for unknown values instead of
 * relying on Jackson to throw an opaque deserialization error.
 *
 * <p>{@code data} is deliberately {@link Object}, not {@code Map<String, Object>} — the
 * PouetPouet-mirroring frontend ({@code board.store.ts}) sends an object payload for most
 * actions ({@code { id, posX, posY }}...) but a bare string for {@code board:join}/
 * {@code board:leave} (just the board id — see {@code CanvasActionService#asMap}, which
 * safely coerces either shape to a possibly-empty map for handlers that need field access).
 *
 * <p>Type-specific payload shape (object-shaped {@code data} only):
 * <ul>
 *   <li>{@code CURSOR_MOVE} (wire {@code board:cursor}): {@code { x, y }}</li>
 *   <li>{@code DRAW}: {@code { type, tool, payload }}</li>
 *   <li>{@code UNDO}: {@code { eventId }}</li>
 *   <li>{@code CARD_*} (wire {@code card:*}): see each handler in
 *       {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasActionService}</li>
 * </ul>
 *
 * @param type the action type string; resolved via {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType#fromWire}
 * @param data type-specific payload — a JSON object, a bare string, or {@code null}
 */
public record CanvasActionMessage(String type, Object data) {
}
