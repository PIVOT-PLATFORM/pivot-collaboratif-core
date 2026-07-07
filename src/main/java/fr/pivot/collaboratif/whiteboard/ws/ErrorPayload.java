package fr.pivot.collaboratif.whiteboard.ws;

/**
 * Payload sent to {@code /user/queue/errors} when a STOMP frame (SUBSCRIBE or SEND)
 * is rejected by the {@link WhiteboardChannelInterceptor}.
 *
 * <p>The {@code error} field carries a human-readable reason (not localised — intended
 * for developer debugging, not end-user display).
 */
public record ErrorPayload(String error) {
}
