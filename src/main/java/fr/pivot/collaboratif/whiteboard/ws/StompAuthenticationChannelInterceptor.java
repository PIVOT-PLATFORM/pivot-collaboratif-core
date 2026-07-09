package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.util.Optional;

/**
 * STOMP channel interceptor that authenticates the {@code CONNECT} frame — the first STOMP
 * message on every WebSocket session — before any {@code SUBSCRIBE}/{@code SEND} can occur
 * (EN08.3, ADR-022).
 *
 * <p><strong>Why authentication lives here and not at the HTTP handshake (design history).</strong>
 * An earlier version of this class validated the bearer token as an {@code Authorization} HTTP
 * header on the WebSocket handshake request itself ({@code StompHandshakeInterceptor}, now
 * removed), mirroring the REST layer's convention. That does not actually work: the browser-native
 * {@code WebSocket} constructor exposes no way to set custom request headers on the handshake at
 * all — a platform limitation, not a library gap — and most WebSocket client implementations
 * (confirmed directly against Tomcat's, used by this repo's own integration tests) reserve the
 * literal {@code Authorization} header name for their own HTTP Basic/Digest authenticator
 * machinery rather than forwarding a caller-supplied value verbatim. Putting the token in a URL
 * query parameter instead was considered and rejected: PIVOT's standing security posture treats a
 * token appearing anywhere in a URL (access/proxy logs, browser history) as unacceptable exposure.
 *
 * <p>The STOMP protocol itself is not subject to either problem: {@code @stomp/rx-stomp}'s
 * {@code connectHeaders} option lets the client attach arbitrary headers (including
 * {@code Authorization}) to the {@code CONNECT} frame, which travels as an ordinary message
 * over the already-established WebSocket connection — never as an HTTP header, never in a URL.
 * Authentication therefore moves one step later in the lifecycle (post-upgrade, first STOMP
 * frame, instead of pre-upgrade) with no security regression: {@code SUBSCRIBE}/{@code SEND}
 * (board room access) are only possible after a successful {@code CONNECT}, so rejection still
 * happens before any board/room access is possible — just one layer down.
 *
 * <p>On success, calls {@link StompHeaderAccessor#setUser(java.security.Principal)} with a
 * {@link StompPrincipal} — per Spring's documented STOMP authentication mechanism, this
 * associates the resolved identity with the STOMP session for every subsequent frame (read by
 * {@link WhiteboardChannelInterceptor} via {@code accessor.getUser()}), superseding whatever
 * (here: nothing — see {@link StompHandshakeHandler}) principal the WebSocket session itself
 * carried at handshake time.
 *
 * <p>On failure, never leaks whether the header was absent, malformed, or the token itself was
 * unknown/expired/revoked/deactivated (same generic-rejection principle as the REST resolver):
 * force-closes the underlying session via {@link WhiteboardSessionRegistry} (defense in depth,
 * same dual notify-then-close pattern already used by {@link WhiteboardChannelInterceptor} for
 * rate-limit violations) and throws a {@link MessagingException} with a generic message, which
 * Spring's STOMP support turns into an ERROR frame back to the client.
 *
 * <p>Registered <strong>before</strong> {@link WhiteboardChannelInterceptor} on the client inbound
 * channel ({@link fr.pivot.collaboratif.config.WebSocketConfig#configureClientInboundChannel}) —
 * authentication must run before authorization, and {@code ChannelInterceptor}s execute in
 * registration order.
 */
@Component
public class StompAuthenticationChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(StompAuthenticationChannelInterceptor.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REJECTION_MESSAGE = "Unauthorized";

    private final AuthenticatedPrincipalResolver principalResolver;
    private final WhiteboardSessionRegistry sessionRegistry;

    /**
     * Constructs the interceptor with the shared {@link AuthenticatedPrincipalResolver} bean
     * and the session registry used to force-close a session on rejected authentication.
     *
     * @param principalResolver the bean that validates a raw bearer token against {@code
     *                          public.access_tokens}/{@code public.users}/{@code public.tenants}
     * @param sessionRegistry   registry used to force-close a session whose CONNECT frame fails
     *                          authentication
     */
    public StompAuthenticationChannelInterceptor(
            final AuthenticatedPrincipalResolver principalResolver,
            final WhiteboardSessionRegistry sessionRegistry) {
        this.principalResolver = principalResolver;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Authenticates {@code CONNECT} frames; every other STOMP command passes through unchanged
     * (authorization for those is {@link WhiteboardChannelInterceptor}'s responsibility, which
     * relies on the {@link StompPrincipal} this method establishes).
     *
     * @param message the inbound STOMP message
     * @param channel the inbound channel
     * @return the message unchanged for non-CONNECT commands or a successfully authenticated
     *     CONNECT
     * @throws MessagingException if the CONNECT frame's bearer token is missing, malformed, or
     *     rejected — Spring's STOMP support turns this into a generic ERROR frame
     */
    @Override
    public Message<?> preSend(final Message<?> message, final MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String rawToken = extractBearerToken(accessor.getFirstNativeHeader(AUTHORIZATION_HEADER));
        if (rawToken == null) {
            LOG.warn("STOMP CONNECT rejected: missing or malformed Authorization header");
            return reject(message, accessor.getSessionId());
        }

        Optional<AuthenticatedPrincipal> principal = principalResolver.resolve(rawToken);
        if (principal.isEmpty()) {
            LOG.warn("STOMP CONNECT rejected: bearer token rejected");
            return reject(message, accessor.getSessionId());
        }

        accessor.setUser(new StompPrincipal(principal.get().userId(), principal.get().tenantId()));
        return message;
    }

    /**
     * Force-closes the session (defense in depth) and throws to trigger Spring's own generic
     * STOMP ERROR frame — never leaks the rejection reason.
     *
     * @param message   the rejected CONNECT message
     * @param sessionId the STOMP session ID to force-close, if present
     * @return never returns normally
     * @throws MessagingException always
     */
    private Message<?> reject(final Message<?> message, final String sessionId) {
        if (sessionId != null) {
            sessionRegistry.close(sessionId, CloseStatus.NOT_ACCEPTABLE);
        }
        throw new MessagingException(message, REJECTION_MESSAGE);
    }

    /**
     * Extracts the raw token from a native STOMP {@code Authorization} header value, requiring
     * a case-insensitive {@code Bearer } prefix — same convention as {@code
     * fr.pivot.collaboratif.context.RequestPrincipalResolver}.
     *
     * @param authorizationHeader the raw native STOMP header value, may be {@code null}
     * @return the raw token, or {@code null} if the header is absent, malformed, or the prefix
     *     does not match
     */
    private static String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
