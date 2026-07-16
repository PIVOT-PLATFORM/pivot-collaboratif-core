package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CardFieldValue} entities (US08.10.1, extended US08.10.2).
 *
 * <p>US08.10.1 added only {@link #findByCardIdAndFieldId} (the lookup that seeds the upsert).
 * US08.10.2 adds the board-scoped clear ({@link #deleteByCardIdAndFieldId}) and the per-card batch
 * read ({@link #findByCardId}) used to populate each card's {@code fieldValues} in the
 * {@code board:state} reply. Removal of a value when its {@link BoardField} or {@link Card} is
 * deleted stays handled entirely by the database FK {@code ON DELETE CASCADE}; the explicit delete
 * here is the user-driven {@code cardfield:clear}, scoped by the {@code (card, field)} pair.
 */
public interface CardFieldValueRepository extends JpaRepository<CardFieldValue, UUID> {

    /**
     * Finds the value a card carries for a given field, if any.
     *
     * @param cardId  the card UUID
     * @param fieldId the field UUID
     * @return the value if present; empty otherwise
     */
    Optional<CardFieldValue> findByCardIdAndFieldId(UUID cardId, UUID fieldId);

    /**
     * Returns every value set on a card, across all of its board's fields — used to populate the
     * card's {@code fieldValues} array in the {@code board:state} reply on JOIN (US08.10.2).
     *
     * @param cardId the card UUID
     * @return the card's values; empty if none are set
     */
    List<CardFieldValue> findByCardId(UUID cardId);

    /**
     * Clears the value a card carries for a given field ({@code cardfield:clear}, US08.10.2).
     * Idempotent: clearing a {@code (card, field)} pair with no stored value simply returns
     * {@code 0} — never an exception. Scoping is by the {@code (card, field)} pair (the
     * {@code card_field_value} table has no {@code boardId} column); the handler resolves and
     * enforces board ownership separately before calling this.
     *
     * @param cardId  the card UUID
     * @param fieldId the field UUID
     * @return the number of rows deleted (0 or 1)
     */
    long deleteByCardIdAndFieldId(UUID cardId, UUID fieldId);
}
