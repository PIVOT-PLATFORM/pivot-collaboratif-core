package fr.pivot.collaboratif.context;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Resolves a {@link RequestPrincipal} from the {@code X-Pivot-User-Id} and
 * {@code X-Pivot-Tenant-Id} HTTP request headers.
 *
 * <p>Returns HTTP 401 Unauthorized if either header is missing or cannot be parsed
 * as a valid UUID.
 *
 * <p>TODO: replace with SecurityContext extraction when pivot-core-starter adds auth (EN17)
 */
public class RequestPrincipalResolver implements HandlerMethodArgumentResolver {

    private static final String HEADER_USER_ID = "X-Pivot-User-Id";
    private static final String HEADER_TENANT_ID = "X-Pivot-Tenant-Id";

    /**
     * Returns {@code true} if the parameter type is {@link RequestPrincipal}.
     *
     * @param parameter the method parameter to check
     * @return {@code true} when the parameter type matches
     */
    @Override
    public boolean supportsParameter(final MethodParameter parameter) {
        return RequestPrincipal.class.equals(parameter.getParameterType());
    }

    /**
     * Resolves the {@link RequestPrincipal} from HTTP headers.
     *
     * @param parameter     the method parameter being resolved
     * @param mavContainer  the model and view container (unused)
     * @param webRequest    the current web request
     * @param binderFactory the binder factory (unused)
     * @return a {@link RequestPrincipal} populated with userId and tenantId
     * @throws ResponseStatusException with HTTP 401 if headers are absent or contain invalid UUIDs
     */
    @Override
    public Object resolveArgument(
            final MethodParameter parameter,
            final ModelAndViewContainer mavContainer,
            final NativeWebRequest webRequest,
            final WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing request context");
        }

        String rawUserId = request.getHeader(HEADER_USER_ID);
        String rawTenantId = request.getHeader(HEADER_TENANT_ID);

        UUID userId = parseUuid(rawUserId, HEADER_USER_ID);
        UUID tenantId = parseUuid(rawTenantId, HEADER_TENANT_ID);

        return new RequestPrincipal(userId, tenantId);
    }

    /**
     * Parses a UUID string from an HTTP header value.
     *
     * @param value      the raw header value, may be {@code null}
     * @param headerName the header name, used in the error message
     * @return the parsed UUID
     * @throws ResponseStatusException with HTTP 401 if the value is missing or invalid
     */
    private UUID parseUuid(final String value, final String headerName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Missing required header: " + headerName);
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid UUID in header " + headerName + ": " + value);
        }
    }
}
