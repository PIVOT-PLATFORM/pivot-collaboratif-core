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
 * <p>Rows are exclusively seeded via Flyway ({@code V1__schema_init.sql}) — there is no
 * user-facing endpoint to create, update, or delete a template in the Socle (see the US
 * "Hors périmètre" section). The entity is therefore read-only from the application's
 * point of view: no setters, and no {@code @GeneratedValue} since ids are fixed constants
 * in the seed data.
 *
 * <p>{@code tenantId} is {@code null} for every row produced in the Socle (global public
 * templates only, see the Gate 1 resolution in the backlog US); it stays nullable so a
 * future tenant-scoped template feature (US30.4.2, phase-3) does not require a breaking
 * migration.
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
    private UUID tenantId;

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
     * @return the tenant UUID, or {@code null}
     */
    public UUID getTenantId() {
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
