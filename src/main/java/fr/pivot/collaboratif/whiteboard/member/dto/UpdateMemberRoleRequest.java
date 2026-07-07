package fr.pivot.collaboratif.whiteboard.member.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing a board member's role.
 *
 * @param role the new role — must not be null; {@link BoardRole#OWNER} is rejected at the service
 *             layer (ownership transfer is out of scope)
 */
public record UpdateMemberRoleRequest(@NotNull BoardRole role) {
}
