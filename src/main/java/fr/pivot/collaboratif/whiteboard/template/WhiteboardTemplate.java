package fr.pivot.collaboratif.whiteboard.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing a whiteboard template header (US08.4.1).
 *
 * <p>Global (public, {@code tenant_id IS NULL}) rows are exclusively seeded via Flyway
 * ({@code V1__schema_init.sql}) — there is no user-facing endpoint to create, update, or
 * delete a <em>global</em> template in the Socle (see the US "Hors périmètre" section).
 *
 * <p>{@code tenantId} was originally {@code null} for every row produced in the Socle
 * (global public templates only); US08.2.4 ("Enregistrer comme template") introduces the
 * first application-created rows, with a non-null {@code tenantId} — a private, per-tenant
 * template distinct from the 3 seeded global ones. The {@link #WhiteboardTemplate(UUID, Long,
 * String, String, String)} constructor exists for that write path; the no-arg constructor
 * remains for JPA hydration of both seeded and application-created rows alike.
 */
@Entity
@Table(name = "whiteboard_template", schema = "collaboratif")
public class WhiteboardTemplate {

    /** Fixed UUID assigned in the Flyway seed data. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Owning tenant, or {@code null} for a global public template. */
    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    /** Stable machine-readable identifier (e.g. {@code "BRAINSTORM"}), used by the
     * frontend as an i18n key prefix ({@code whiteboard.template.<code>.*}). */
    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    /** Default display name (fallback if the frontend i18n lookup misses). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Default short description (fallback if the frontend i18n lookup misses). */
    @Column(name = "description", length = 500)
    private String description;

    /** URL of the template's gallery preview image, or {@code null} if none. */
    @Column(name = "thumbnail_url", length = 255)
    private String thumbnailUrl;

    /** Ordering position within the template gallery. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** Server-side creation timestamp. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** No-arg constructor required by JPA. */
    protected WhiteboardTemplate() {
    }

    /**
     * Creates a new tenant-owned (private) template — used by the "save as template"
     * flow (US08.2.4). {@code code} is derived from {@code id} to satisfy the column's
     * {@code UNIQUE NOT NULL} constraint without colliding with any seeded global template's
     * fixed code.
     *
     * @param id          server-generated UUID
     * @param tenantId    owning tenant's {@code public.tenants.id} (never {@code null} for
     *                    application-created templates)
     * @param name        display name (1–100 chars, validated at the controller layer)
     * @param description optional short description (up to 500 chars), or {@code null}
     * @param thumbnailUrl gallery preview image URL, or {@code null}
     */
    public WhiteboardTemplate(
            final UUID id,
            final Long tenantId,
            final String name,
            final String description,
            final String thumbnailUrl) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = "CUSTOM_" + id;
        this.name = name;
        this.description = description;
        this.thumbnailUrl = thumbnailUrl;
        this.displayOrder = 0;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Returns the template's unique identifier.
     *
     * @return the UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning tenant, or {@code null} for a global public template.
     *
     * @return the tenant's {@code public.tenants.id}, or {@code null}
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Returns the stable machine-readable template code.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the default display name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the default short description.
     *
     * @return the description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the gallery preview image URL.
     *
     * @return the thumbnail URL, or {@code null}
     */
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    /**
     * Returns the display order within the template gallery.
     *
     * @return the display order
     */
    public int getDisplayOrder() {
        return displayOrder;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return the createdAt instant
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
