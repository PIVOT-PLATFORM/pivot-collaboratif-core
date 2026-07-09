package fr.pivot.collaboratif.exception;

/**
 * Thrown when the whiteboard module is disabled for the caller's tenant.
 *
 * <p>Mapped to HTTP 403 Forbidden by {@link GlobalExceptionHandler}.
 */
public class WhiteboardModuleDisabledException extends RuntimeException {

    /**
     * Creates a module-disabled exception for the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} of the tenant for which the whiteboard
     *                 module is inactive
     */
    public WhiteboardModuleDisabledException(final Long tenantId) {
        super("Whiteboard module is disabled for tenant: " + tenantId);
    }
}
