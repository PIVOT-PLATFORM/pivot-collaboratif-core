package fr.pivot.collaboratif.whiteboard.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * HTTP handshake interceptor that enforces caller identity before the WebSocket
 * upgrade completes.
 *
 * <p>Reads the {@code X-Pivot-User-Id} and {@code X-Pivot-Tenant-Id} request headers,
 * parses them as UUIDs, and stores the parsed values in the WebSocket session
 * attributes so that {@link StompHandshakeHandler} can build a {@link StompPrincipal}.
 * If either header is absent or contains an invalid UUID the interceptor sets HTTP 401
 * and returns {@code false}, aborting the upgrade without establishing a session.
 *
 * <p>TODO: replace header extraction with opaque-token validation once
 * {@code fr.pivot:pivot-core-starter} is published (EN17).
 */
public class StompHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(StompHandshakeInterceptor.class);
    private static final String HEADER_USER_ID = "X-Pivot-User-Id";
    private static final String HEADER_TENANT_ID = "X-Pivot-Tenant-Id";
    static final String ATTR_USER_ID = "principalUserId";
    static final String ATTR_TENANT_ID = "principalTenantId";

    /**
     * Validates identity headers and populates session attributes.
     *
     * @param request    the incoming HTTP upgrade request
     * @param response   the HTTP response; set to 401 on validation failure
     * @param wsHandler  the target WebSocket handler (not used)
     * @param attributes mutable session attributes map; populated on success
     * @return {@code true} when both UUIDs are present and valid; {@code false} otherwise
     */
    @Override
    public boolean beforeHandshake(
            final ServerHttpRequest request,
            final ServerHttpResponse response,
            final WebSocketHandler wsHandler,
            final Map<String, Object> attributes) {

        String rawUserId = request.getHeaders().getFirst(HEADER_USER_ID);
        String rawTenantId = request.getHeaders().getFirst(HEADER_TENANT_ID);

        UUID userId = parseUuid(rawUserId);
        UUID tenantId = parseUuid(rawTenantId);

        if (userId == null || tenantId == null) {
            LOG.warn("WebSocket handshake rejected: missing or invalid identity headers "
                    + "(userId={}, tenantId={})", rawUserId, rawTenantId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_TENANT_ID, tenantId);
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
     * Parses a UUID from a raw string value.
     *
     * @param value the raw header value, may be {@code null}
     * @return the parsed {@link UUID}, or {@code null} if the value is absent or invalid
     */
    private UUID parseUuid(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
