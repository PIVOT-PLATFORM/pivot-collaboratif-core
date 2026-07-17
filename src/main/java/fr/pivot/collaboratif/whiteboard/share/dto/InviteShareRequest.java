package fr.pivot.collaboratif.whiteboard.share.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for inviting a user by e-mail (POST /whiteboard/boards/{boardId}/shares/invite,
 * US08.2.5).
 *
 * @param email the invitee's e-mail — must be a syntactically valid, non-blank address
 * @param role  the role granted; {@code null} defaults to {@link BoardRole#VIEWER} at the service
 *              layer. An {@code EDITOR} manager requesting {@link BoardRole#OWNER} is rejected 403.
 */
public record InviteShareRequest(
        @NotBlank(message = "INVALID_EMAIL")
        @Email(message = "INVALID_EMAIL")
        @Size(max = 320, message = "INVALID_EMAIL")
        String email,
        BoardRole role) {
}
