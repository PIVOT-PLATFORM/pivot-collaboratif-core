package fr.pivot.collaboratif.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Disables Spring Security's default auto-configured protection (EN08.3).
 *
 * <p>{@code fr.pivot:pivot-core-starter} transitively brings {@code spring-boot-starter-security}
 * onto this module's classpath (needed elsewhere in the starter for {@code pivot-core}'s own
 * OIDC/opaque-token stack). Without an explicit {@link SecurityFilterChain} bean, Spring Boot's
 * default auto-configuration challenges <strong>every</strong> request with HTTP Basic —
 * empirically confirmed via a raw socket handshake dump: a plain WebSocket upgrade request to
 * {@code /api/collaboratif/ws/whiteboard} came back {@code 401} with
 * {@code WWW-Authenticate: Basic realm="Realm"} before this fix, well before reaching any
 * application code. This module implements its own bearer-token authentication entirely outside
 * Spring Security — {@link fr.pivot.collaboratif.context.RequestPrincipalResolver} for REST,
 * {@link fr.pivot.collaboratif.whiteboard.ws.StompAuthenticationChannelInterceptor} for
 * WebSocket/STOMP (ADR-022: validation duplicated locally against {@code public.*}, never a
 * network call to {@code pivot-core}) — so Spring Security's own filter chain is configured here
 * to permit every request unconditionally and never challenge, leaving all real authn/authz
 * enforcement to that application-level code.
 *
 * <p>CSRF is disabled: this is a stateless bearer-token API (no cookie-based session), for which
 * CSRF protection does not apply.
 */
@Configuration
public class SecurityConfig {

    /**
     * Permits every request through Spring Security unconditionally and disables the default
     * HTTP Basic / form-login challenge — see class JavaDoc for why.
     *
     * @param http the security configuration builder
     * @return the configured filter chain
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
