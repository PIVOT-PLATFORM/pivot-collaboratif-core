package fr.pivot.collaboratif.whiteboard.join;

import fr.pivot.collaboratif.context.RequestPrincipal;
import fr.pivot.collaboratif.whiteboard.join.dto.JoinBoardResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for joining a whiteboard board via a share token.
 *
 * <p>Exposes {@code POST /whiteboard/join?token={token}} as the single entry point.
 * Rate limiting (10 attempts per hour per user and per IP) is enforced by
 * {@link JoinRateLimitService} before any token validation occurs.
 */
@RestController
@RequestMapping("/whiteboard/join")
public class BoardJoinController {

    private final BoardJoinService boardJoinService;
    private final JoinRateLimitService rateLimitService;

    /**
     * Creates the controller with its required service dependencies.
     *
     * @param boardJoinService   the join business logic service
     * @param rateLimitService   the Redis-backed rate limiter
     */
    public BoardJoinController(
            final BoardJoinService boardJoinService,
            final JoinRateLimitService rateLimitService) {
        this.boardJoinService = boardJoinService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * Processes a board join request using a share token from the query string.
     *
     * <p>Validates that the {@code token} parameter is non-blank before any database
     * lookup (400 INVALID_TOKEN_FORMAT if blank). Rate limits are checked and
     * incremented before token validation — failed attempts count against the limit.
     *
     * @param token     the plain share token from the invitation link query parameter
     * @param principal the resolved caller identity (userId + tenantId from headers)
     * @param request   the raw HTTP request, used to extract the client IP address
     * @return the join response with board details and the redirect URL (HTTP 200)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public JoinBoardResponse join(
            @RequestParam final String token,
            final RequestPrincipal principal,
            final HttpServletRequest request) {

        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN_FORMAT");
        }

        String clientIp = resolveClientIp(request);
        rateLimitService.checkAndIncrement(principal.userId(), clientIp);

        return boardJoinService.join(token, principal.userId(), principal.tenantId());
    }

    /**
     * Resolves the client's real IP address, checking the {@code X-Forwarded-For} header
     * first (populated by nginx/load balancer) before falling back to the direct remote address.
     *
     * @param request the HTTP servlet request
     * @return the client's IP address string
     */
    private String resolveClientIp(final HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
