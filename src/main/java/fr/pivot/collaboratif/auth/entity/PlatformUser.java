package fr.pivot.collaboratif.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only mirror of {@code public.users}, owned and written exclusively by {@code pivot-core}
 * — never persisted, updated, or deleted from this repo (EN08.3, ADR-022).
 *
 * <p>Mapped explicitly to schema {@code public}. Deliberately excludes every profile field
 * (email, password hash, locale, avatar…) — this repo only needs {@code tenant_id}, {@code
 * role}, and {@code is_active} to validate a bearer token and resolve the minimal {@code
 * AuthenticatedPrincipal}, mirroring the same exclusion already made by {@code
 * fr.pivot.core.auth.AuthenticatedPrincipal} itself.
 */
@Entity
@Table(schema = "public", name = "users")
public class PlatformUser {

    @Id
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "role", nullable = false, length = 50)
    private String role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** No-argument constructor required by JPA. */
    protected PlatformUser() {
    }

    /** @return database primary key ({@code public.users.id}) */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the Spring Security role, e.g. {@code ROLE_USER} */
    public String getRole() {
        return role;
    }

    /** @return {@code true} unless an admin has deactivated this account */
    public boolean isActive() {
        return active;
    }
}
