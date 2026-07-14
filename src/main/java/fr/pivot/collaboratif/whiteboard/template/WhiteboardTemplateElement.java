package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.whiteboard.canvas.CanvasElementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA entity representing a single drawable element of a whiteboard template (US08.4.1).
 *
 * <p>Global template rows are exclusively seeded via Flyway ({@code V1__schema_init.sql});
 * there is no user-facing authoring endpoint for <em>global</em> templates in the Socle (see
 * the US "Hors périmètre" section). US08.2.4 ("save as template") introduces application-
 * created rows for tenant-owned templates, snapshotting a board's current {@code DRAW}
 * canvas events verbatim (see {@code WhiteboardTemplateService#createFromBoard}) — these rows
 * are <strong>not</strong> validated against the strict shape/text/image whitelist. Only
 * elements belonging to the 3 seeded global templates are re-validated by
 * {@code CanvasElementValidator} at board-initialization time (see
 * {@code WhiteboardTemplateService#initializeBoard}); that method does not currently accept
 * tenant-owned templates at all (see {@code WhiteboardTemplateService#resolveGlobalTemplate}).
 *
 * <p>{@code templateId} is a plain column rather than a JPA association, mirroring the
 * convention used by {@code CanvasEvent#boardId} elsewhere in this module.
 */
@Entity
@Table(name = "whiteboard_template_element", schema = "collaboratif")
public class WhiteboardTemplateElement {

    /** UUID generated server-side (or by the database default in seed data). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Template this element belongs to. */
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    /** Whitelisted element kind (shape/text/image). */
    @Enumerated(EnumType.STRING)
    @Column(name = "element_type", nullable = false, length = 20, updatable = false)
    private CanvasElementType elementType;

    /**
     * JSON payload describing the element's geometry and type-specific fields, stored as
     * JSONB. See {@code CanvasElementValidator} for the exact schema per {@link #elementType}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String payload;

    /** Ordering position within the template, preserved when replayed onto a new board. */
    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    /** No-arg constructor required by JPA. */
    protected WhiteboardTemplateElement() {
    }

    /**
     * Creates a new template element — used by the "save as template" flow (US08.2.4) to
     * snapshot a board's current canvas content.
     *
     * @param id           server-generated UUID
     * @param templateId   owning template's UUID
     * @param elementType  whitelisted element kind
     * @param payload      JSON payload string; validated against {@code CanvasElementValidator}
     *                     only for Flyway-seeded global templates — the "save as template"
     *                     write path (US08.2.4) stores the raw {@code CanvasEvent} envelope
     *                     verbatim and does not validate it against this schema (see
     *                     {@code WhiteboardTemplateService#createFromBoard})
     * @param displayOrder ordering position, preserving the original canvas event order
     */
    public WhiteboardTemplateElement(
            final UUID id,
            final UUID templateId,
            final CanvasElementType elementType,
            final String payload,
            final int displayOrder) {
        this.id = id;
        this.templateId = templateId;
        this.elementType = elementType;
        this.payload = payload;
        this.displayOrder = displayOrder;
    }

    /**
     * Returns the element's unique identifier.
     *
     * @return the UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning template's UUID.
     *
     * @return the template UUID
     */
    public UUID getTemplateId() {
        return templateId;
    }

    /**
     * Returns the whitelisted element kind.
     *
     * @return the element type
     */
    public CanvasElementType getElementType() {
        return elementType;
    }

    /**
     * Returns the JSON payload string.
     *
     * @return the payload
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Returns the display order within the template.
     *
     * @return the display order
     */
    public int getDisplayOrder() {
        return displayOrder;
    }
}
