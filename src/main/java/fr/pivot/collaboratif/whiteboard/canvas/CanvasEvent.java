package fr.pivot.collaboratif.whiteboard.canvas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing a persisted canvas event on a whiteboard board.
 *
 * <p>Only {@link CanvasEventType#DRAW} events are persisted (US08.3.1). Other event
 * types (JOIN, LEAVE, CURSOR_MOVE, UNDO) are ephemeral and broadcast-only. The
 * {@code type} column exists for schema forward-compatibility should future stories
 * decide to persist other event types.
 *
 * <p>The {@code payload} column is stored as JSONB and contains the opaque, tool-specific
 * draw data (stroke points, shape dimensions, text content, move deltas, etc.). The server
 * does not interpret the payload beyond JSON validity; schema validation of individual
 * sub-fields is delegated to US08.3.1's controller layer.
 *
 * <p>Conflict strategy: Last-Write-Wins — later events for the same board overwrite
 * earlier ones visually on the canvas. No OT/CRDT is implemented in the Socle.
 */
@Entity
@Table(name = "canvas_event", schema = "collaboratif")
public class CanvasEvent {

    /** UUID generated server-side — never provided by the client. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Board to which this event belongs. */
    @Column(name = "board_id", nullable = false, updatable = false)
    private UUID boardId;

    /** Tenant owning this board — stored for tenant-scoped history queries. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** User who emitted this event. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Canvas event type (only DRAW persisted in the Socle). */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private CanvasEventType eventType;

    /**
     * Opaque JSON payload from the client, stored as JSONB.
     * Contains tool-specific draw data: {@code { type, tool, payload }}.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} instructs Hibernate to bind this
     * {@code String} field as a JSON-type parameter, which PostgreSQL accepts for
     * JSONB columns (implicit cast from JSON to JSONB). Without it, Hibernate uses
     * {@code setString()} which PostgreSQL rejects with "expression is of type
     * character varying" for a JSONB target column.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", updatable = false)
    private String payload;

    /** Server-side creation timestamp. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** No-arg constructor required by JPA. */
    protected CanvasEvent() {
    }

    /**
     * Creates a new canvas event.
     *
     * @param id        server-generated UUID
     * @param boardId   the board UUID
     * @param tenantId  the tenant UUID
     * @param userId    the emitting user UUID
     * @param eventType the canvas event type
     * @param payload   JSON payload string (may be {@code null} for non-DRAW types)
     * @param createdAt server-side creation timestamp
     */
    public CanvasEvent(
            final UUID id,
            final UUID boardId,
            final UUID tenantId,
            final UUID userId,
            final CanvasEventType eventType,
            final String payload,
            final OffsetDateTime createdAt) {
        this.id = id;
        this.boardId = boardId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    /**
     * Returns the event UUID.
     *
     * @return the event UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the board UUID.
     *
     * @return the board UUID
     */
    public UUID getBoardId() {
        return boardId;
    }

    /**
     * Returns the tenant UUID.
     *
     * @return the tenant UUID
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * Returns the user UUID.
     *
     * @return the user UUID
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * Returns the canvas event type.
     *
     * @return the event type
     */
    public CanvasEventType getEventType() {
        return eventType;
    }

    /**
     * Returns the JSON payload string.
     *
     * @return the payload, or {@code null}
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
