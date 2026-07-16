package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire representation of a {@link fr.pivot.collaboratif.whiteboard.canvas.Card}, used both in
 * the board-state reply sent on {@code JOIN} and in every {@code CARD_*} broadcast (EN08.4).
 *
 * <p>Deliberately not the JPA entity itself — entities are never serialised directly to clients
 * (see this repo's standing rule against exposing JPA entities in API payloads).
 *
 * @param id         the card's UUID as a string
 * @param type       the {@link fr.pivot.collaboratif.whiteboard.canvas.CardType} name
 * @param content    the type-specific content
 * @param meta       the OpenGraph metadata cache (US08.6.5), or {@code null} until enriched
 * @param posX       the X position
 * @param posY       the Y position
 * @param width      the width
 * @param height     the height
 * @param color      the hex colour
 * @param groupId    the group UUID as a string, or {@code null} if ungrouped
 * @param groupColor the group outline hex colour, or {@code null}
 * @param locked     whether the card is locked
 * @param layer      the Z-order layer
 * @param fieldValues the card's custom field values (US08.10.2), one per set
 *                    {@link fr.pivot.collaboratif.whiteboard.canvas.BoardField}; empty (never
 *                    {@code null}) when none are set — carried in {@code board:state} so a late
 *                    joiner sees values set in another session, matching the frontend's
 *                    {@code Card.fieldValues}
 */
public record CardDto(
        String id,
        String type,
        String content,
        Map<String, Object> meta,
        double posX,
        double posY,
        double width,
        double height,
        String color,
        String groupId,
        String groupColor,
        boolean locked,
        int layer,
        List<FieldValueDto> fieldValues) {

    /**
     * Builds a {@link CardDto} from a persisted card and its already-parsed {@code meta}.
     *
     * @param id         the card UUID
     * @param type       the card type name
     * @param content    the content
     * @param meta       the parsed meta map, or {@code null}
     * @param posX       the X position
     * @param posY       the Y position
     * @param width      the width
     * @param height     the height
     * @param color      the hex colour
     * @param groupId    the group UUID, or {@code null}
     * @param groupColor the group outline colour, or {@code null}
     * @param locked     whether locked
     * @param layer      the layer
     * @param fieldValues the card's custom field values (empty, never {@code null}, when none set)
     * @return a new {@link CardDto}
     */
    public static CardDto of(
            final UUID id,
            final String type,
            final String content,
            final Map<String, Object> meta,
            final double posX,
            final double posY,
            final double width,
            final double height,
            final String color,
            final UUID groupId,
            final String groupColor,
            final boolean locked,
            final int layer,
            final List<FieldValueDto> fieldValues) {
        return new CardDto(
                id.toString(), type, content, meta, posX, posY, width, height, color,
                groupId != null ? groupId.toString() : null, groupColor, locked, layer, fieldValues);
    }
}
