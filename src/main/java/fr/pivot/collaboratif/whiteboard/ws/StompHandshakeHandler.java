package fr.pivot.collaboratif.whiteboard.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Handshake handler that builds a {@link StompPrincipal} from session attributes
 * populated by {@link StompHandshakeInterceptor}.
 *
 * <p>Spring calls {@link #determineUser} at the end of a successful HTTP upgrade
 * to attach a {@link Principal} to the WebSocket session. That principal is then
 * available on every subsequent STOMP frame via
 * {@code StompHeaderAccessor.getUser()}.
 */
public class StompHandshakeHandler extends DefaultHandshakeHandler {

    /**
     * Constructs the session {@link Principal} from the identity attributes set during
     * the HTTP handshake.
     *
     * @param request    the HTTP upgrade request (not used directly)
     * @param wsHandler  the target WebSocket handler (not used directly)
     * @param attributes the session attributes populated by {@link StompHandshakeInterceptor}
     * @return a {@link StompPrincipal} if both UUIDs are present, {@code null} otherwise
     */
    @Override
    protected Principal determineUser(
            final ServerHttpRequest request,
            final WebSocketHandler wsHandler,
            final Map<String, Object> attributes) {

        UUID userId = (UUID) attributes.get(StompHandshakeInterceptor.ATTR_USER_ID);
        UUID tenantId = (UUID) attributes.get(StompHandshakeInterceptor.ATTR_TENANT_ID);
        if (userId == null || tenantId == null) {
            return null;
        }
        return new StompPrincipal(userId, tenantId);
    }
}
