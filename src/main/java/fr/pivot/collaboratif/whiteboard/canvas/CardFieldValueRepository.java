package fr.pivot.collaboratif.whiteboard.canvas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CardFieldValue} entities (US08.10.1).
 *
 * <p>Deliberately minimal for this US: only the lookup needed to build on the value table exists
 * here. The set/clear (upsert) queries belong to US08.10.2 and are not added yet. Deletion of a
 * value when its {@link BoardField} or {@link Card} is removed is handled entirely by the database
 * FK {@code ON DELETE CASCADE}, so no delete method is exposed here.
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
}
