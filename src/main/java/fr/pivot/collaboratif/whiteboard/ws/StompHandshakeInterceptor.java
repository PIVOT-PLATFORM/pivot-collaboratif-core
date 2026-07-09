package fr.pivot.collaboratif.whiteboard.ws;

import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * HTTP handshake interceptor that enforces caller identity before the WebSocket
 * upgrade completes.
 *
 * <p>Reads the {@code Authorization} request header from the SockJS/STOMP handshake HTTP
 * request, extracts a case-insensitive {@code Bearer } token exactly like {@code
 * fr.pivot.collaboratif.context.RequestPrincipalResolver} does for the REST layer, and
 * delegates validation to the injected {@link AuthenticatedPrincipalResolver} bean
 * (implemented by {@link fr.pivot.collaboratif.auth.TokenValidationService} — EN08.3, ADR-022).
 * On success, the resolved {@code Long userId}/{@code Long tenantId} are stored in the
 * WebSocket session attributes so that {@link StompHandshakeHandler} can build a
 * {@link StompPrincipal}. On a missing/malformed header or an empty {@link Optional} from
 * {@code resolve}, the interceptor sets a generic HTTP 401 and returns {@code false},
 * aborting the upgrade without establishing a session — never leaking whether the header was
 * absent, malformed, or the token itself was unknown/expired/revoked/deactivated (same
 * generic-401 principle as the REST resolver).
 *
 * <p><strong>Handshake token convention (judgment call).</strong> There is no pre-existing
 * documented convention for how a client authenticates a STOMP/SockJS handshake in this
 * codebase — this class previously trusted client-supplied {@code X-Pivot-User-Id}/{@code
 * X-Pivot-Tenant-Id} headers with zero verification (the same cross-tenant authentication
 * bypass EN08.3 closed for the REST layer). Absent any other documented convention, this
 * implementation reuses the REST layer's {@code Authorization: Bearer <token>} header on the
 * handshake HTTP request itself, for consistency with {@code RequestPrincipalResolver} — this
 * is an interpretation, not a confirmed contract with the Angular {@code @stomp/rx-stomp}
 * client in {@code pivot-collaboratif-ui}, and should be verified against that repo's actual
 * handshake wiring.
 */
public class StompHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(StompHandshakeInterceptor.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    static final String ATTR_USER_ID = "principalUserId";
    static final String ATTR_TENANT_ID = "principalTenantId";

    private final AuthenticatedPrincipalResolver principalResolver;

    /**
     * Constructs the interceptor with the shared {@link AuthenticatedPrincipalResolver} bean.
     *
     * @param principalResolver the bean that validates a raw bearer token against {@code
     *                          public.access_tokens}/{@code public.users}/{@code public.tenants}
     */
    public StompHandshakeInterceptor(final AuthenticatedPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    /**
     * Validates the {@code Authorization} bearer token and populates session attributes.
     *
     * @param request    the incoming HTTP upgrade request
     * @param response   the HTTP response; set to 401 on validation failure
     * @param wsHandler  the target WebSocket handler (not used)
     * @param attributes mutable session attributes map; populated on success
     * @return {@code true} when the bearer token resolves to a valid principal; {@code false}
     *     otherwise
     */
    @Override
    public boolean beforeHandshake(
            final ServerHttpRequest request,
            final ServerHttpResponse response,
            final WebSocketHandler wsHandler,
            final Map<String, Object> attributes) {

        String rawToken = extractBearerToken(
                request.getHeaders().getFirst(AUTHORIZATION_HEADER));
        if (rawToken == null) {
            LOG.warn("WebSocket handshake rejected: missing or malformed Authorization header");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Optional<AuthenticatedPrincipal> principal = principalResolver.resolve(rawToken);
        if (principal.isEmpty()) {
            LOG.warn("WebSocket handshake rejected: bearer token rejected");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(ATTR_USER_ID, principal.get().userId());
        attributes.put(ATTR_TENANT_ID, principal.get().tenantId());
        return true;
    }

    /**
     * No post-handshake action required.
     *
     * @param request   the HTTP upgrade request
     * @param response  the HTTP response
     * @param wsHandler the target WebSocket handler
     * @param exception any exception raised during the handshake, or {@code null}
     */
    @Override
    public void afterHandshake(
            final ServerHttpRequest request,
            final ServerHttpResponse response,
            final WebSocketHandler wsHandler,
            final Exception exception) {
        // no-op
    }

    /**
     * Extracts the raw token from an {@code Authorization} header value, requiring a
     * case-insensitive {@code Bearer } prefix — same convention as {@code
     * fr.pivot.collaboratif.context.RequestPrincipalResolver}.
     *
     * @param authorizationHeader the raw {@code Authorization} header value, may be {@code null}
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
