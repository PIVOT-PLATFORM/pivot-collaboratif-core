package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.List;

/**
 * Server-emitted presence update broadcast to {@code /topic/whiteboard/{boardId}/presence}
 * whenever a participant JOINs or LEAVEs a canvas room.
 *
 * <p>Contains the complete current list of connected participants so that clients do not
 * need to maintain incremental state. A new subscriber receives the PARTICIPANTS_UPDATE
 * emitted at their own JOIN as the initial state.
 *
 * @param participants ordered list of all currently connected participants
 */
public record ParticipantsUpdatePayload(List<ParticipantInfo> participants) {
}
