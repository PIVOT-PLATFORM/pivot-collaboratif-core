package fr.pivot.collaboratif.context;

/**
 * Represents the authenticated caller identity resolved from a validated bearer token.
 *
 * <p>Carries the real platform identities from {@code public.users}/{@code public.tenants}
 * ({@code BIGSERIAL}/{@code Long} — never {@code UUID}, there is no UUID identity concept
 * anywhere in {@code pivot-core}'s schema), resolved by {@link RequestPrincipalResolver} from
 * the {@code Authorization: Bearer} header via {@link fr.pivot.core.auth.AuthenticatedPrincipalResolver}
 * (EN08.3, ADR-022). Replaces the previous unauthenticated {@code X-Pivot-User-Id}/{@code
 * X-Pivot-Tenant-Id} header mechanism.
 *
 * @param userId   the caller's {@code public.users.id}
 * @param tenantId the caller's {@code public.tenants.id}
 */
public record RequestPrincipal(Long userId, Long tenantId) {}
