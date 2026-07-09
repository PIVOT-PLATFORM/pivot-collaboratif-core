package fr.pivot.collaboratif.whiteboard.board;

/**
 * Contract for checking whether the whiteboard module is enabled for a given tenant.
 *
 * <p>Implementations may consult a local flag, a remote module registry, or any other
 * activation source. The default implementation always returns {@code true} until the
 * pivot-core-starter module registry is available and consumable as a dependency (EN17).
 *
 * <p>TODO: replace with a call to {@code ModuleAccessService} from pivot-core-starter
 * once it exports per-tenant module activation status (EN17 / ModuleStatusService).
 */
public interface WhiteboardModuleCheck {

    /**
     * Returns {@code true} if the whiteboard module is active for the given tenant.
     *
     * @param tenantId the tenant's {@code public.tenants.id} to check
     * @return {@code true} when whiteboard features are accessible
     */
    boolean isEnabled(Long tenantId);
}
