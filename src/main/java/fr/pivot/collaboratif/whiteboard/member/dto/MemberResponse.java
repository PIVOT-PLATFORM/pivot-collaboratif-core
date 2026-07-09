package fr.pivot.collaboratif.whiteboard.member.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;

import java.time.Instant;

/**
 * Read-only DTO representing a board member's identity, role, and join timestamp.
 *
 * @param userId   the member's {@code public.users.id}
 * @param role     the member's current role on the board
 * @param joinedAt the instant the user joined the board
 */
public record MemberResponse(Long userId, BoardRole role, Instant joinedAt) {

    /**
     * Builds a {@link MemberResponse} from a {@link BoardMember} entity.
     *
     * @param member the board member entity
     * @return the response record
     */
    public static MemberResponse from(final BoardMember member) {
        return new MemberResponse(
                member.getId().getUserId(),
                member.getRole(),
                member.getJoinedAt());
    }
}
