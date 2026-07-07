package fr.pivot.collaboratif.context;

import java.util.UUID;

/**
 * Represents the authenticated caller identity extracted from request headers.
 *
 * <p>Carries the user and tenant UUIDs resolved by {@link RequestPrincipalResolver}
 * from the {@code X-Pivot-User-Id} and {@code X-Pivot-Tenant-Id} headers.
 *
 * <p>TODO: replace with SecurityContext extraction when pivot-core-starter adds auth (EN17)
 */
public record RequestPrincipal(UUID userId, UUID tenantId) {}
