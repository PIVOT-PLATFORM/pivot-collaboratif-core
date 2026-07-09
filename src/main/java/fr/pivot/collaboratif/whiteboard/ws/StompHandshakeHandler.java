package fr.pivot.collaboratif.whiteboard.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * Handshake handler that deliberately assigns <strong>no</strong> identity at HTTP handshake
 * time (EN08.3).
 *
 * <p>A custom {@code Authorization} header cannot be set on a WebSocket handshake from browser
 * JavaScript, and a token in the handshake URL is unacceptable exposure (server/proxy logs,
 * browser history) — so this repo authenticates one step later, on the first STOMP frame
 * ({@code CONNECT}) instead, via {@link StompAuthenticationChannelInterceptor} (see that class's
 * JavaDoc for the full rationale). Explicitly returning {@code null} here — rather than relying
 * on {@link DefaultHandshakeHandler}'s own default behavior — makes that design intent
 * unambiguous: the WebSocket session starts anonymous, and {@link StompAuthenticationChannelInterceptor}
 * establishes the real {@link StompPrincipal} via {@code accessor.setUser(...)} once the CONNECT
 * frame's bearer token is validated, which Spring then propagates as the session {@link
 * Principal} for every subsequent STOMP frame.
 */
public class StompHandshakeHandler extends DefaultHandshakeHandler {

    /**
     * Always returns {@code null} — no identity is established at HTTP handshake time. See the
     * class JavaDoc.
     *
     * @param request    the HTTP upgrade request (not used)
     * @param wsHandler  the target WebSocket handler (not used)
     * @param attributes the session attributes (not used)
     * @return always {@code null}
     */
    @Override
    protected Principal determineUser(
            final ServerHttpRequest request,
            final WebSocketHandler wsHandler,
            final Map<String, Object> attributes) {
        return null;
    }
}
