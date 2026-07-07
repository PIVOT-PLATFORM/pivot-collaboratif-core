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
 * <p>Rows are exclusively seeded via Flyway ({@code V1__schema_init.sql}); there is no
 * user-facing authoring endpoint in the Socle (see the US "Hors périmètre" section). At
 * board-initialization time, each element is converted into a persisted
 * {@code CanvasEvent} of type {@code DRAW} on the new board (see
 * {@code WhiteboardTemplateService#initializeBoard}), after being re-validated by
 * {@code CanvasElementValidator} against the same strict shape/text/image whitelist.
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
