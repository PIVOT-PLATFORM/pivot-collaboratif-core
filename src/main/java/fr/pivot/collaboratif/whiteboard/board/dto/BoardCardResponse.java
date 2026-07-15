package fr.pivot.collaboratif.whiteboard.board.dto;

import fr.pivot.collaboratif.whiteboard.canvas.Card;

import java.util.Map;
import java.util.UUID;

/**
 * Wire representation of a {@link Card} embedded in {@link BoardResponse#cards()}, returned by
 * {@code GET /whiteboard/boards/{boardId}} (US08.1.9, parity spec §2.2).
 *
 * <p>Deliberately a distinct type from {@code fr.pivot.collaboratif.whiteboard.canvas.dto.CardDto}
 * (the WebSocket board-state/broadcast wire shape, EN08.4) rather than a shared one — this
 * endpoint's contract is owned by the board-loading feature (US08.1.9) and evolves
 * independently from the realtime canvas wire protocol, which several other US in the current
 * sprint (US08.6.x, card types) are actively extending in parallel.
 *
 * <p>{@code fieldValues} is the reference spec's per-card "field values" concept (parity §2.2):
 * in this codebase it is realised as the existing {@link Card#getMeta()} opaque JSONB cache
 * (already used for the {@code LINK} card type's OpenGraph preview, US08.6.5), exposed under
 * the {@code fieldValues} wire name for this endpoint's contract — no new column or table was
 * introduced. {@code null} until a card has been enriched with metadata.
 *
 * @param id         the card's UUID as a string
 * @param type       the {@link fr.pivot.collaboratif.whiteboard.canvas.CardType} name
 * @param content    the type-specific content
 * @param fieldValues the parsed per-card metadata/field-values map, or {@code null} if none
 * @param posX       the X position
 * @param posY       the Y position
 * @param width      the width
 * @param height     the height
 * @param color      the hex colour
 * @param groupId    the group UUID as a string, or {@code null} if ungrouped
 * @param groupColor the group outline hex colour, or {@code null}
 * @param locked     whether the card is locked
 * @param layer      the Z-order layer
 */
public record BoardCardResponse(
        String id,
        String type,
        String content,
        Map<String, Object> fieldValues,
        double posX,
        double posY,
        double width,
        double height,
        String color,
        String groupId,
        String groupColor,
        boolean locked,
        int layer) {

    /**
     * Builds a {@link BoardCardResponse} from a persisted {@link Card} and its already-parsed
     * {@code fieldValues} map.
     *
     * @param card        the persisted card
     * @param fieldValues the parsed field-values map (from {@link Card#getMeta()}), or
     *                    {@code null}
     * @return a new {@link BoardCardResponse}
     */
    public static BoardCardResponse of(final Card card, final Map<String, Object> fieldValues) {
        UUID groupId = card.getGroupId();
        return new BoardCardResponse(
                card.getId().toString(),
                card.getType().name(),
                card.getContent(),
                fieldValues,
                card.getPosX(),
                card.getPosY(),
                card.getWidth(),
                card.getHeight(),
                card.getColor(),
                groupId != null ? groupId.toString() : null,
                card.getGroupColor(),
                card.isLocked(),
                card.getLayer());
    }
}
