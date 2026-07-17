package fr.pivot.collaboratif.whiteboard.share.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only DTO representing a board share (a {@link BoardMember} row) as exposed by the
 * {@code /shares} governance API (US08.2.5).
 *
 * @param shareId  the board-independent surrogate identifier of the share row
 * @param userId   the member's {@code public.users.id}
 * @param role     the member's current role on the board
 * @param joinedAt the instant the share was created / the user joined
 */
public record ShareResponse(UUID shareId, Long userId, BoardRole role, Instant joinedAt) {

    /**
     * Builds a {@link ShareResponse} from a {@link BoardMember} entity.
     *
     * @param member the board member (share) entity
     * @return the response record
     */
    public static ShareResponse from(final BoardMember member) {
        return new ShareResponse(
                member.getShareId(),
                member.getId().getUserId(),
                member.getRole(),
                member.getJoinedAt());
    }
}
