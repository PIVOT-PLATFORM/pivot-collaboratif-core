package fr.pivot.collaboratif.whiteboard.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Listens for STOMP session disconnect events to maintain the presence liveness registry.
 *
 * <p>Presence itself is driven exclusively by the application-level JOIN/LEAVE messages
 * handled by {@code CanvasActionService} (US08.3.1) — this listener no longer reacts to
 * {@code SessionSubscribeEvent}. A mere STOMP {@code SUBSCRIBE} to a board's main topic does
 * not represent participation; only an explicit JOIN action does (resolution of #32).
 *
 * <p>{@link SessionDisconnectEvent} remains handled here: when the WebSocket session closes
 * for any reason (explicit close, crash, or the 30 s silent-disconnect STOMP heartbeat timeout
 * configured in {@code WebSocketConfig}), {@link WhiteboardPresenceRegistry#handleDisconnect}
 * is invoked to release the session's liveness bookkeeping and, only if it was the user's last
 * active session on the board, clear presence and broadcast the update.
 */
@Component
public class WhiteboardWebSocketEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteboardWebSocketEventListener.class);

    private final WhiteboardPresenceRegistry presenceRegistry;

    /**
     * Creates the listener with the presence registry dependency.
     *
     * @param presenceRegistry the registry to update on session disconnect
     */
    public WhiteboardWebSocketEventListener(final WhiteboardPresenceRegistry presenceRegistry) {
        this.presenceRegistry = presenceRegistry;
    }

    /**
     * Releases the disconnecting session's liveness bookkeeping for every board room it had
     * joined via an application-level JOIN.
     *
     * @param event the disconnect event published by Spring when a WebSocket session closes
     */
    @EventListener
    public void handleSessionDisconnect(final SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            LOG.debug("SessionDisconnect with no sessionId — skipping presence cleanup");
            return;
        }
        LOG.debug("WebSocket session disconnect: session={}", sessionId);
        presenceRegistry.handleDisconnect(sessionId);
    }
}
