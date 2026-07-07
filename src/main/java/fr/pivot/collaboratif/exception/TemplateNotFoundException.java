package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when a whiteboard template identifier does not resolve to an existing global
 * template ({@code tenant_id IS NULL}).
 *
 * <p>Used for both "does not exist at all" and, defensively, "exists but is not a global
 * template" — a single exception for both cases avoids leaking whether a given UUID
 * corresponds to a real (but inaccessible) row. In the Socle no tenant-scoped template can
 * exist (see US08.4.1 Gate 1 resolution), so only the first case can actually occur today.
 */
public class TemplateNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given template identifier.
     *
     * @param templateId the UUID of the template that could not be found
     */
    public TemplateNotFoundException(final UUID templateId) {
        super("Template not found: " + templateId);
    }
}
