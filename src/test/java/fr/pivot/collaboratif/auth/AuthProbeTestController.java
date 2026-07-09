package fr.pivot.collaboratif.auth;

import fr.pivot.collaboratif.context.RequestPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test-only endpoint exercising the full {@link RequestPrincipal} resolution pipeline
 * (Authorization header -> {@link fr.pivot.collaboratif.context.RequestPrincipalResolver} ->
 * {@link TokenValidationService}) end-to-end over real HTTP, decoupled from any whiteboard
 * feature endpoint (EN08.3).
 *
 * <p>Picked up by {@code @SpringBootTest}'s component scan ({@code fr.pivot.collaboratif}
 * covers this test package too) — never packaged in the production JAR since it lives under
 * {@code src/test/java}.
 */
@RestController
class AuthProbeTestController {

    /**
     * Returns the resolved caller identity — HTTP 200 only if the bearer token is valid.
     *
     * @param principal the resolved caller identity
     * @return the resolved userId/tenantId
     */
    @GetMapping("/test/auth/whoami")
    Map<String, Long> whoami(final RequestPrincipal principal) {
        return Map.of("userId", principal.userId(), "tenantId", principal.tenantId());
    }
}
