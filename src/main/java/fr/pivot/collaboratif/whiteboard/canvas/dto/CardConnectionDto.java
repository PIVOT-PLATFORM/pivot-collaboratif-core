package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.UUID;

/**
 * Wire representation of a {@link fr.pivot.collaboratif.whiteboard.canvas.CardConnection}, used
 * both in the board-state reply sent on {@code JOIN} and in the {@code connection:created}
 * broadcast (US08.7.1).
 *
 * <p>Deliberately not the JPA entity itself — entities are never serialised directly to clients
 * (see this repo's standing rule against exposing JPA entities in API payloads).
 *
 * @param id     the connector's UUID as a string
 * @param fromId the source card's UUID as a string
 * @param toId   the target card's UUID as a string
 * @param label  the connector label, or {@code null} until styled (US08.7.2)
 * @param color  the connector hex colour, or {@code null} until styled (US08.7.2)
 * @param shape  the connector line shape (fixed default {@code curved} at creation)
 * @param arrow  the connector arrowhead style (fixed default {@code none} at creation)
 * @param dashed whether the connector line is dashed (fixed default {@code false} at creation)
 * @param width  the connector line stroke width (fixed default 2 at creation)
 * @param lineStyle the line style — {@code solid}/{@code dashed}/{@code dotted} (US08.7.2, V6);
 *                  supersedes {@code dashed}, which is kept for connectors stored before it
 * @param startCap  the shape at the connector's start (US08.7.2, V6)
 * @param endCap    the shape at the connector's end (US08.7.2, V6)
 */
public record CardConnectionDto(
        String id,
        String fromId,
        String toId,
        String label,
        String color,
        String shape,
        String arrow,
        boolean dashed,
        int width,
        String lineStyle,
        String startCap,
        String endCap) {

    /**
     * Builds a {@link CardConnectionDto} from a persisted connector's fields.
     *
     * @param id     the connector UUID
     * @param fromId the source card UUID
     * @param toId   the target card UUID
     * @param label  the label, or {@code null}
     * @param color  the hex colour, or {@code null}
     * @param shape  the line shape
     * @param arrow  the arrowhead style
     * @param dashed whether the line is dashed
     * @param width  the line stroke width
     * @param lineStyle the line style
     * @param startCap  the shape at the start
     * @param endCap    the shape at the end
     * @return a new {@link CardConnectionDto}
     */
    public static CardConnectionDto of(
            final UUID id,
            final UUID fromId,
            final UUID toId,
            final String label,
            final String color,
            final String shape,
            final String arrow,
            final boolean dashed,
            final int width,
            final String lineStyle,
            final String startCap,
            final String endCap) {
        return new CardConnectionDto(
                id.toString(), fromId.toString(), toId.toString(), label, color, shape, arrow, dashed, width,
                lineStyle, startCap, endCap);
    }
}
