package fr.pivot.collaboratif.exception;

import java.util.UUID;

/**
 * Thrown when the whiteboard module is disabled for the caller's tenant.
 *
 * <p>Mapped to HTTP 403 Forbidden by {@link GlobalExceptionHandler}.
 */
public class WhiteboardModuleDisabledException extends RuntimeException {

    /**
     * Creates a module-disabled exception for the given tenant.
     *
     * @param tenantId the UUID of the tenant for which the whiteboard module is inactive
     */
    public WhiteboardModuleDisabledException(final UUID tenantId) {
        super("Whiteboard module is disabled for tenant: " + tenantId);
    }
}
