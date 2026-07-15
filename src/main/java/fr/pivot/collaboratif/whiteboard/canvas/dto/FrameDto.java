package fr.pivot.collaboratif.whiteboard.canvas.dto;

import java.util.UUID;

/**
 * Wire representation of a {@link fr.pivot.collaboratif.whiteboard.canvas.Frame}, used both in
 * the {@code board:state} reply sent on {@code JOIN} and in the {@code frame:created}/
 * {@code frame:moved}/{@code frame:resized}/{@code frame:updated} broadcasts (EN08, Frames).
 *
 * <p>Field names and order mirror the frontend's {@code Frame} model ({@code board.types.ts})
 * exactly — {@code {id, boardId, title, posX, posY, width, height, color, active, layer}} — so
 * that a flattened broadcast (see {@code CanvasActionService#toFlatMap}) is consumed directly by
 * the frontend's {@code this.on<Frame>('frame:created', …)} handlers, which read the fields off
 * {@code data} at the top level rather than from a nested envelope.
 *
 * <p>Deliberately not the JPA entity itself — entities are never serialised directly to clients
 * (see this repo's standing rule against exposing JPA entities in API payloads).
 *
 * @param id      the frame's UUID as a string
 * @param boardId the owning board's UUID as a string
 * @param title   the frame title
 * @param posX    the X position
 * @param posY    the Y position
 * @param width   the width
 * @param height  the height
 * @param color   the hex colour
 * @param active  whether the frame is active (carries its cards when dragged)
 * @param layer   the Z-order layer
 */
public record FrameDto(
        String id,
        String boardId,
        String title,
        double posX,
        double posY,
        double width,
        double height,
        String color,
        boolean active,
        int layer) {

    /**
     * Builds a {@link FrameDto} from a persisted frame's fields.
     *
     * @param id      the frame UUID
     * @param boardId the owning board UUID
     * @param title   the title
     * @param posX    the X position
     * @param posY    the Y position
     * @param width   the width
     * @param height  the height
     * @param color   the hex colour
     * @param active  whether active
     * @param layer   the layer
     * @return a new {@link FrameDto}
     */
    public static FrameDto of(
            final UUID id,
            final UUID boardId,
            final String title,
            final double posX,
            final double posY,
            final double width,
            final double height,
            final String color,
            final boolean active,
            final int layer) {
        return new FrameDto(
                id.toString(), boardId.toString(), title, posX, posY, width, height, color, active, layer);
    }
}
