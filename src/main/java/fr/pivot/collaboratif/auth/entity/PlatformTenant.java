package fr.pivot.collaboratif.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only mirror of {@code public.tenants}, owned and written exclusively by {@code
 * pivot-core} — never persisted, updated, or deleted from this repo (EN08.3, ADR-022).
 *
 * <p>Mapped explicitly to schema {@code public}. Only {@code tenant_invalidation_timestamp} is
 * needed — the bulk tenant-deactivation revocation check duplicated from {@code
 * fr.pivot.auth.service.TokenService#isTenantInvalidated}.
 */
@Entity
@Table(schema = "public", name = "tenants")
public class PlatformTenant {

    @Id
    private Long id;

    @Column(name = "tenant_invalidation_timestamp")
    private Instant tenantInvalidationTimestamp;

    /** No-argument constructor required by JPA. */
    protected PlatformTenant() {
    }

    /** @return database primary key ({@code public.tenants.id}) */
    public Long getId() {
        return id;
    }

    /**
     * @return the timestamp of this tenant's last deactivation, or {@code null} if the tenant
     *     was never deactivated
     */
    public Instant getTenantInvalidationTimestamp() {
        return tenantInvalidationTimestamp;
    }
}
