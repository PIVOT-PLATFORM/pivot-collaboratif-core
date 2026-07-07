package fr.pivot.collaboratif.whiteboard.share.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for generating a board share token (POST /whiteboard/boards/{id}/share).
 *
 * @param role     the role granted to joining users — must be EDITOR or VIEWER
 * @param maxUses  maximum number of times the link may be used (defaults to 1 if absent)
 * @param ttlDays  number of days until the token expires (defaults to 7 if absent, max 30)
 */
public record ShareBoardRequest(
        @NotNull BoardRole role,
        @Min(1) @Max(100) Integer maxUses,
        @Min(1) @Max(30) Integer ttlDays) {
}
