package fr.pivot.collaboratif.context;

import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves a {@link RequestPrincipal} from the {@code Authorization: Bearer} HTTP request
 * header, delegating the actual token validation to the injected {@link
 * AuthenticatedPrincipalResolver} bean (EN08.3, ADR-022 — {@link
 * fr.pivot.collaboratif.auth.TokenValidationService} in this repo).
 *
 * <p>Replaces the previous stub that trusted the client-supplied {@code X-Pivot-User-Id}/{@code
 * X-Pivot-Tenant-Id} headers with zero verification (cross-tenant authentication bypass).
 *
 * <p>Returns HTTP 401 Unauthorized — with a generic message, never leaking whether the header
 * was missing, malformed, or the token itself was unknown/expired/revoked/tenant-deactivated/
 * user-deactivated — if the {@code Authorization} header is absent, does not use a
 * case-insensitive {@code Bearer } prefix, or the resolved token is rejected.
 */
@Component
public class RequestPrincipalResolver implements HandlerMethodArgumentResolver {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_MESSAGE = "Unauthorized";

    private final AuthenticatedPrincipalResolver principalResolver;

    /**
     * Constructs the resolver with the shared {@link AuthenticatedPrincipalResolver} bean.
     *
     * @param principalResolver the bean that validates a raw bearer token against {@code
     *                          public.access_tokens}/{@code public.users}/{@code public.tenants}
     */
    public RequestPrincipalResolver(final AuthenticatedPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

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
     * Resolves the {@link RequestPrincipal} from the {@code Authorization} header.
     *
     * @param parameter     the method parameter being resolved
     * @param mavContainer  the model and view container (unused)
     * @param webRequest    the current web request
     * @param binderFactory the binder factory (unused)
     * @return a {@link RequestPrincipal} populated from the validated bearer token
     * @throws ResponseStatusException with HTTP 401 if the header is missing/malformed or the
     *     token is rejected
     */
    @Override
    public Object resolveArgument(
            final MethodParameter parameter,
            final ModelAndViewContainer mavContainer,
            final NativeWebRequest webRequest,
            final WebDataBinderFactory binderFactory) {

        final HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw unauthorized();
        }

        final String rawToken = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (rawToken == null) {
            throw unauthorized();
        }

        final AuthenticatedPrincipal principal = principalResolver.resolve(rawToken)
                .orElseThrow(RequestPrincipalResolver::unauthorized);

        return new RequestPrincipal(principal.userId(), principal.tenantId());
    }

    /**
     * Extracts the raw token from an {@code Authorization} header value, requiring a
     * case-insensitive {@code Bearer } prefix.
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

    /**
     * Builds the generic 401 thrown for every rejection case — never leaks the reason.
     *
     * @return a {@link ResponseStatusException} carrying HTTP 401 and a generic message
     */
    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }
}
