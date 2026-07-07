package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.Map;

/**
 * Incoming STOMP canvas action sent by a client on
 * {@code /app/whiteboard/{boardId}/action}.
 *
 * <p>The {@code type} field is a raw string validated against the
 * {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType} whitelist in
 * {@link fr.pivot.collaboratif.whiteboard.canvas.WhiteboardActionController}.
 * Keeping {@code type} as a plain {@code String} allows the controller to produce
 * a structured WARN log for unknown values instead of relying on Jackson to throw
 * an opaque deserialization error.
 *
 * <p>The {@code data} map is the type-specific payload (opaque to the transport):
 * <ul>
 *   <li>{@code JOIN}: {@code { displayName, avatarUrl? }}</li>
 *   <li>{@code LEAVE}: {@code {} } (empty — userId comes from the session principal)</li>
 *   <li>{@code DRAW}: {@code { type, tool, payload }}</li>
 *   <li>{@code CURSOR_MOVE}: {@code { x, y }}</li>
 *   <li>{@code UNDO}: {@code { eventId }}</li>
 * </ul>
 *
 * @param type the action type string; must match a {@link fr.pivot.collaboratif.whiteboard.canvas.CanvasEventType} value
 * @param data type-specific payload fields; may be {@code null} for LEAVE
 */
public record CanvasActionMessage(String type, Map<String, Object> data) {
}
