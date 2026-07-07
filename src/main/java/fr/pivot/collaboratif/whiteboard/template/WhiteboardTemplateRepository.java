package fr.pivot.collaboratif.whiteboard.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link WhiteboardTemplate} entities.
 */
public interface WhiteboardTemplateRepository extends JpaRepository<WhiteboardTemplate, UUID> {

    /**
     * Returns all global public templates ({@code tenant_id IS NULL}), ordered for gallery
     * display.
     *
     * @return the ordered list of global templates
     */
    List<WhiteboardTemplate> findAllByTenantIdIsNullOrderByDisplayOrderAsc();

    /**
     * Finds a global public template by id.
     *
     * <p>Returns {@link Optional#empty()} both when no row exists for the id and when a row
     * exists but has a non-null {@code tenant_id} — a single outcome for both cases avoids
     * leaking the existence of a template that does not belong to the caller's scope.
     *
     * @param id the template UUID
     * @return an {@link Optional} containing the template, or empty if not found or not global
     */
    Optional<WhiteboardTemplate> findByIdAndTenantIdIsNull(UUID id);
}
