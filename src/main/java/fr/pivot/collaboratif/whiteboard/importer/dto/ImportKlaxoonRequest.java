package fr.pivot.collaboratif.whiteboard.importer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /whiteboard/boards/{boardId}/import/klaxoon} (US08.13.1) — an
 * already-structured JSON representation of a Klaxoon board export (cards, connectors, frames,
 * custom field declarations). Parsing the raw {@code .klx} binary into this shape is a client
 * (frontend) concern, out of scope for this backend (see the US's "Hors périmètre").
 *
 * <p>Card types are restricted to the five importable kinds ({@link #cards()}' {@code type}) —
 * {@code LINK} and {@code TABLE} are deliberately excluded (the reference Klaxoon schema does not
 * carry them either). Field {@code type} is restricted to {@code TEXT}/{@code SELECT} — the two
 * kinds a Klaxoon field declaration can be; a target board field of type {@code NUMBER}/
 * {@code DATE} can still be <em>reused</em> by name (case-insensitively) if one already exists,
 * just never freshly declared by an import.
 *
 * @param cards       the cards to import; each carries the client-supplied {@code klxId} used to
 *                    build the {@code idMap} (klxId → server id) for connector remapping
 * @param connections the connectors to import; kept only if both {@code fromKlxId}/{@code toKlxId}
 *                    resolve through the {@code idMap} — others are silently dropped
 * @param frames      the frames to import, or {@code null}/empty for none
 * @param fields      the custom field declarations to import (reused by case-insensitive name if
 *                    a matching {@link fr.pivot.collaboratif.whiteboard.canvas.BoardField} already
 *                    exists on the board, else freshly created), or {@code null}/empty for none
 */
public record ImportKlaxoonRequest(
        @NotNull @Valid List<ImportCardRequest> cards,
        @NotNull @Valid List<ImportConnectionRequest> connections,
        @Valid List<ImportFrameRequest> frames,
        @Valid List<ImportFieldRequest> fields) {

    /**
     * One card to import, keyed by its Klaxoon-side {@code klxId} (never a server id — the server
     * assigns a real id on insert and builds the {@code idMap} from this value).
     *
     * @param klxId       the Klaxoon-side card identifier, used only to build the {@code idMap}
     *                    for connector remapping — never persisted
     * @param type        the card type; restricted to {@code TEXT}/{@code LABEL}/{@code DRAW}/
     *                    {@code IMAGE}/{@code SHAPE}
     * @param content     the type-specific content
     * @param color       the hex colour, or {@code null} to keep the card's default
     * @param posX        the X position (never shifted by the anti-collision offset)
     * @param posY        the Y position (shifted by {@code offsetY} before persistence)
     * @param width       the card width
     * @param height      the card height
     * @param zIndex      the Z-order layer
     * @param locked      whether the card is locked
     * @param groupKey    the Klaxoon-side group key; cards sharing the same {@code groupKey} are
     *                    assigned one fresh server-generated {@code groupId}, or {@code null} if
     *                    ungrouped
     * @param fieldValues the card's custom field values, or {@code null}/empty for none
     */
    public record ImportCardRequest(
            @NotBlank String klxId,
            @NotBlank
            @Pattern(regexp = "^(TEXT|LABEL|DRAW|IMAGE|SHAPE)$", message = "INVALID_CARD_TYPE")
            String type,
            String content,
            @Size(max = 20) String color,
            double posX,
            double posY,
            double width,
            double height,
            int zIndex,
            boolean locked,
            String groupKey,
            @Valid List<ImportFieldValueRequest> fieldValues) {
    }

    /**
     * One connector to import, referencing its endpoints by their Klaxoon-side {@code klxId}s
     * (resolved through the {@code idMap} built while inserting {@link #cards()}).
     *
     * @param fromKlxId the source card's Klaxoon-side identifier
     * @param toKlxId   the target card's Klaxoon-side identifier
     * @param shape     the connector line shape, or {@code null} to keep the default
     * @param color     the connector hex colour, or {@code null}
     * @param arrow     the connector arrowhead style, or {@code null} to keep the default
     * @param label     the connector label, or {@code null}
     * @param width     the connector line stroke width, or {@code null} to keep the default
     * @param dashed    whether the connector line is dashed
     */
    public record ImportConnectionRequest(
            @NotBlank String fromKlxId,
            @NotBlank String toKlxId,
            @Size(max = 20) String shape,
            @Size(max = 20) String color,
            @Size(max = 20) String arrow,
            String label,
            Integer width,
            boolean dashed) {
    }

    /**
     * One frame to import. Frames carry no {@code klxId}: nothing in the import payload
     * references a frame by id (unlike cards, which connectors reference).
     *
     * @param title  the frame title, or {@code null} to default to an empty title
     * @param posX   the X position (never shifted by the anti-collision offset)
     * @param posY   the Y position (shifted by {@code offsetY} before persistence)
     * @param width  the frame width
     * @param height the frame height
     */
    public record ImportFrameRequest(
            @Size(max = 200) String title,
            double posX,
            double posY,
            double width,
            double height) {
    }

    /**
     * One custom field declaration to import, reused by case-insensitive name if a matching
     * {@link fr.pivot.collaboratif.whiteboard.canvas.BoardField} already exists on the board.
     *
     * @param name    the field name (matched case-insensitively against existing board fields)
     * @param type    the field type; restricted to {@code TEXT}/{@code SELECT}
     * @param options the allowed values for a {@code SELECT} field, or {@code null} otherwise
     */
    public record ImportFieldRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^(TEXT|SELECT)$", message = "INVALID_FIELD_TYPE") String type,
            List<@Size(max = 200) String> options) {
    }

    /**
     * One card's value for a named custom field, resolved (or created) by case-insensitive name
     * against the board's {@link fr.pivot.collaboratif.whiteboard.canvas.BoardField}s.
     *
     * @param field the field name (matched case-insensitively; created as a fresh {@code TEXT}
     *              field if no top-level field declaration and no existing board field match it)
     * @param value the stored value
     */
    public record ImportFieldValueRequest(
            @NotBlank @Size(max = 120) String field,
            @NotNull @Size(max = 2000) String value) {
    }
}
