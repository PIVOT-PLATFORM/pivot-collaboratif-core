package fr.pivot.collaboratif.whiteboard.ws;

import java.util.List;

/**
 * Payload broadcast to {@code /topic/whiteboard/{boardId}/presence} whenever the
 * set of connected participants changes on a board.
 *
 * <p>Contains the UUIDs (as strings) of all users currently subscribed to the board's
 * STOMP room. Sent by {@link WhiteboardPresenceRegistry} on every JOIN and LEAVE event.
 */
public record PresencePayload(List<String> userIds) {
}
