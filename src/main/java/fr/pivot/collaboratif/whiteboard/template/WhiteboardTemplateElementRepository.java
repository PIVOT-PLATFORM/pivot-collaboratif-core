package fr.pivot.collaboratif.whiteboard.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link WhiteboardTemplateElement} entities.
 */
public interface WhiteboardTemplateElementRepository
        extends JpaRepository<WhiteboardTemplateElement, UUID> {

    /**
     * Returns all elements of a template, ordered for deterministic replay onto a new board.
     *
     * @param templateId the template UUID
     * @return the ordered list of elements; empty if the template has none
     */
    List<WhiteboardTemplateElement> findAllByTemplateIdOrderByDisplayOrderAsc(UUID templateId);
}
