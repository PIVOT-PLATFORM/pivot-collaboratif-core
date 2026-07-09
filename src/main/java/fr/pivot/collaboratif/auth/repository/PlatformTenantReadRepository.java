package fr.pivot.collaboratif.auth.repository;

import fr.pivot.collaboratif.auth.entity.PlatformTenant;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.tenants} (EN08.3).
 *
 * <p>Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed: this repo never writes to {@code tenants}.
 */
public interface PlatformTenantReadRepository extends Repository<PlatformTenant, Long> {

    /**
     * Finds a platform tenant by primary key.
     *
     * @param id the {@code public.tenants.id} to look up
     * @return the matching tenant, or empty if none is found
     */
    Optional<PlatformTenant> findById(Long id);
}
