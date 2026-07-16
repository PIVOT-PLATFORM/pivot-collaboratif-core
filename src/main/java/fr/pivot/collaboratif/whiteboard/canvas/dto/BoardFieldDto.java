package fr.pivot.collaboratif.whiteboard.canvas.dto;

import fr.pivot.collaboratif.whiteboard.canvas.BoardField;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Wire representation of a {@link BoardField}, used both in the {@code board:state} reply sent on
 * {@code JOIN} (the {@code fields} array) and in the {@code boardfield:created}/
 * {@code boardfield:updated} broadcasts (US08.10.1).
 *
 * <p>Field names and order mirror the frontend's {@code BoardField} model ({@code board.types.ts})
 * exactly — {@code {id, boardId, name, emoji, type, options, order}} — so that a flattened broadcast
 * (see {@code CanvasActionService#toFlatMap}) is consumed directly by the frontend's
 * {@code this.on<BoardField>('boardfield:created', …)} handlers, which read the fields off
 * {@code data} at the top level rather than from a nested envelope.
 *
 * <p>{@code options} is the JSON array of allowed values (a
 * {@link fr.pivot.collaboratif.whiteboard.canvas.FieldType#SELECT} field's choices) parsed back
 * into a {@link List}; it is {@code null} for every other type. Deliberately
 * not the JPA entity itself — entities are never serialised directly to clients (this repo's
 * standing rule against exposing JPA entities in API payloads).
 *
 * @param id      the field's UUID as a string
 * @param boardId the owning board's UUID as a string
 * @param name    the field name
 * @param emoji   the emoji, or {@code null}
 * @param type    the {@link fr.pivot.collaboratif.whiteboard.canvas.FieldType} name
 * @param options the allowed values (SELECT only), or {@code null}
 * @param order   the display order
 */
public record BoardFieldDto(
        String id,
        String boardId,
        String name,
        String emoji,
        String type,
        List<String> options,
        int order) {

    /** Shared, thread-safe mapper used solely to parse the persisted {@code options} JSON array. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Builds a {@link BoardFieldDto} from a persisted field, parsing the opaque {@code options}
     * JSON string (if present) back into a {@link List}. A {@code null} or unparsable {@code options}
     * yields a {@code null} list (never an exception) — a non-SELECT field simply has no options.
     *
     * @param field the persisted field
     * @return the corresponding {@link BoardFieldDto}
     */
    public static BoardFieldDto of(final BoardField field) {
        return new BoardFieldDto(
                field.getId().toString(),
                field.getBoardId().toString(),
                field.getName(),
                field.getEmoji(),
                field.getType().name(),
                parseOptions(field.getOptions()),
                field.getOrder());
    }

    /**
     * Parses a persisted {@code options} JSON array string into a list of strings, tolerantly:
     * {@code null}/blank input, or any parse failure, resolves to {@code null} rather than throwing.
     *
     * @param options the persisted JSON array string, or {@code null}
     * @return the parsed list, or {@code null}
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseOptions(final String options) {
        if (options == null || options.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(options, List.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
