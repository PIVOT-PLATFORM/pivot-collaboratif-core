package fr.pivot.collaboratif.whiteboard.board;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bootstrap implementation of {@link WhiteboardModuleCheck}.
 *
 * <p>Always returns {@code true} (module considered enabled for all tenants) until
 * the pivot-core-starter exposes per-tenant module activation status.
 *
 * <p>TODO: wire to {@code ModuleAccessService} from pivot-core-starter (EN17)
 */
@Component
public class DefaultWhiteboardModuleCheck implements WhiteboardModuleCheck {

    /**
     * Always returns {@code true}, indicating the whiteboard module is enabled
     * for any tenant.
     *
     * @param tenantId the tenant to check (unused in this bootstrap implementation)
     * @return {@code true}
     */
    @Override
    public boolean isEnabled(final UUID tenantId) {
        return true;
    }
}
