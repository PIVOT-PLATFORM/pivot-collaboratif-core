package fr.pivot.collaboratif.whiteboard.join.dto;

import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;

import java.util.UUID;

/**
 * Response body returned when a user successfully joins a board via a share token.
 *
 * <p>Carries the minimal information required by the Angular client to redirect the
 * user to the board canvas: the board UUID, its title, the assigned role, and the
 * redirect path.
 *
 * @param boardId     the UUID of the joined board
 * @param title       the human-readable board title
 * @param role        the role granted by the share token (EDITOR or VIEWER)
 * @param redirectUrl the frontend path to navigate to after joining
 */
public record JoinBoardResponse(UUID boardId, String title, BoardRole role, String redirectUrl) {

    /**
     * Builds a join response from the persisted board and the token's role.
     *
     * @param board the board the user just joined
     * @param role  the role assigned by the share token
     * @return a fully populated response
     */
    public static JoinBoardResponse from(final Board board, final BoardRole role) {
        return new JoinBoardResponse(
                board.getId(),
                board.getTitle(),
                role,
                "/whiteboard/" + board.getId());
    }
}
