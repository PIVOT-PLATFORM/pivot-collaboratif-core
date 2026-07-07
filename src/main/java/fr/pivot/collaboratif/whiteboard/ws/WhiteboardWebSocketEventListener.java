package fr.pivot.collaboratif.whiteboard.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.UUID;

/**
 * Listens for STOMP session lifecycle events to maintain the presence registry.
 *
 * <p>Two events are handled:
 * <ul>
 *   <li>{@link SessionSubscribeEvent} — when a client successfully subscribes to a direct
 *       board topic (e.g. {@code /topic/whiteboard/{boardId}}), the user is registered in
 *       {@link WhiteboardPresenceRegistry} and the updated participant list is broadcast.
 *       Subscriptions to sub-topics (e.g. {@code /presence}) are deliberately ignored here
 *       because only the main board subscription represents active participation.</li>
 *   <li>{@link SessionDisconnectEvent} — when the WebSocket session closes for any reason,
 *       the user is removed from all board rooms they had joined and presence updates are
 *       broadcast for each affected board.</li>
 * </ul>
 *
 * <p>Note: the {@link SessionSubscribeEvent} fires <em>after</em> the STOMP frame has
 * passed through {@link WhiteboardChannelInterceptor}. A subscription that was denied by
 * the interceptor (returned {@code null}) does not generate a {@link SessionSubscribeEvent},
 * so this listener only sees authorised subscriptions.
 */
@Component
public class WhiteboardWebSocketEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteboardWebSocketEventListener.class);
    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    private final WhiteboardPresenceRegistry presenceRegistry;

    /**
     * Creates the listener with the presence registry dependency.
     *
     * @param presenceRegistry the registry to update on join and leave events
     */
    public WhiteboardWebSocketEventListener(final WhiteboardPresenceRegistry presenceRegistry) {
        this.presenceRegistry = presenceRegistry;
    }

    /**
     * Registers a user in the presence registry when they subscribe to a board's main topic.
     *
     * <p>Only subscriptions whose destination is exactly {@code /topic/whiteboard/{uuid}} are
     * processed; presence sub-topic subscriptions ({@code /presence}) are skipped.
     *
     * @param event the subscribe event published by Spring after the frame is accepted
     */
    @EventListener
    public void handleSessionSubscribe(final SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        UUID boardId = extractDirectBoardId(destination);
        if (boardId == null) {
            return;
        }
        StompPrincipal principal = resolvePrincipal(accessor);
        if (principal == null) {
            return;
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }
        presenceRegistry.join(principal.tenantId(), boardId, principal.userId(), sessionId);
    }

    /**
     * Removes a user from all board rooms they had joined when their session disconnects.
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
        presenceRegistry.leaveAll(sessionId);
    }

    /**
     * Extracts the board UUID from a destination string only when the destination is the
     * direct board topic (not a sub-topic like {@code /presence}).
     *
     * @param destination the STOMP destination string
     * @return the board UUID if the destination matches {@code /topic/whiteboard/{uuid}};
     *         {@code null} otherwise
     */
    private UUID extractDirectBoardId(final String destination) {
        if (!destination.startsWith(BOARD_TOPIC_PREFIX)) {
            return null;
        }
        String after = destination.substring(BOARD_TOPIC_PREFIX.length());
        try {
            return UUID.fromString(after);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolves the {@link StompPrincipal} from the event's message headers.
     *
     * @param accessor the STOMP header accessor for the event message
     * @return the {@link StompPrincipal}, or {@code null} if unavailable
     */
    private StompPrincipal resolvePrincipal(final StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal stompPrincipal) {
            return stompPrincipal;
        }
        return null;
    }
}
