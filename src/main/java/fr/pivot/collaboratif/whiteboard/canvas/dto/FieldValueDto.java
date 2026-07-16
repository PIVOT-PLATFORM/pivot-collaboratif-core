package fr.pivot.collaboratif.whiteboard.canvas.dto;

import fr.pivot.collaboratif.whiteboard.canvas.CardFieldValue;

/**
 * Wire representation of a {@link CardFieldValue} — one card's value for one custom board field
 * ({@link fr.pivot.collaboratif.whiteboard.canvas.BoardField}, US08.10.2).
 *
 * <p>Field names and order mirror the frontend's {@code FieldValue} model ({@code board.types.ts})
 * exactly — {@code {id, cardId, fieldId, value}} — so that a flattened broadcast (see
 * {@code CanvasActionService#toFlatMap}) is consumed directly by the frontend's
 * {@code this.on<FieldValue>('cardfield:updated', …)} handler, which reads the fields off
 * {@code data} at the top level rather than from a nested envelope, and upserts them into the
 * card's {@code fieldValues} list. Also carried inside each {@code CardDto#fieldValues()} array in
 * the {@code board:state} reply so a late joiner sees values already set in another session.
 *
 * <p>Deliberately not the JPA entity itself — entities are never serialised directly to clients
 * (this repo's standing rule against exposing JPA entities in API payloads).
 *
 * @param id      the value's UUID as a string (DB-assigned)
 * @param cardId  the owning card's UUID as a string
 * @param fieldId the field's UUID as a string
 * @param value   the stored value, interpreted according to the field's type
 */
public record FieldValueDto(String id, String cardId, String fieldId, String value) {

    /**
     * Builds a {@link FieldValueDto} from a persisted card field value.
     *
     * @param value the persisted value row
     * @return the corresponding {@link FieldValueDto}
     */
    public static FieldValueDto of(final CardFieldValue value) {
        return new FieldValueDto(
                value.getId().toString(),
                value.getCardId().toString(),
                value.getFieldId().toString(),
                value.getValue());
    }
}
