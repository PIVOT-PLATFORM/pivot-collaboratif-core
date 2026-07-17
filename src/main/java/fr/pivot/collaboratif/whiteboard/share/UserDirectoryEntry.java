package fr.pivot.collaboratif.whiteboard.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Read-only directory view of {@code public.users} used to resolve an invitee by e-mail
 * (US08.2.5). Owned and written exclusively by {@code pivot-core} — never persisted, updated, or
 * deleted from this repo (EN08.3, ADR-022).
 *
 * <p>Distinct from {@code fr.pivot.collaboratif.auth.entity.PlatformUser}, which deliberately
 * omits the {@code email} column because token validation never needs it. Invitation-by-email
 * does, so this narrow projection maps {@code email} in addition to {@code id}, {@code tenant_id}
 * and {@code is_active}. Resolution is always tenant-scoped: a match must belong to the inviting
 * caller's tenant, so an e-mail from another tenant is treated as unknown (404), preventing
 * cross-tenant e-mail enumeration.
 */
@Entity
@Table(schema = "public", name = "users")
public class UserDirectoryEntry {

    @Id
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** No-argument constructor required by JPA. */
    protected UserDirectoryEntry() {
    }

    /** @return the user's {@code public.users.id} */
    public Long getId() {
        return id;
    }

    /** @return the owning {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the user's e-mail address */
    public String getEmail() {
        return email;
    }

    /** @return {@code true} unless an admin has deactivated this account */
    public boolean isActive() {
        return active;
    }
}
