package fr.pivot.collaboratif.whiteboard.share.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing a share's role (PATCH /whiteboard/boards/{boardId}/shares/{shareId},
 * US08.2.5).
 *
 * @param role the new role — required. Governance is enforced at the service layer: an
 *             {@code EDITOR} manager may neither assign {@link BoardRole#OWNER} nor touch a target
 *             that is already {@code OWNER} (403).
 */
public record UpdateShareRoleRequest(@NotNull(message = "INVALID_ROLE") BoardRole role) {
}
