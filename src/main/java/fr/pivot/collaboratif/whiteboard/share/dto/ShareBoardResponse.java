package fr.pivot.collaboratif.whiteboard.share.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.share.BoardShareToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a newly generated board share token.
 *
 * <p>The {@code shareLink} contains the plain invitation token embedded in the join URL.
 * This is the only moment the plain token is visible — it is never stored or logged.
 *
 * @param tokenId   the opaque token identifier (used for revocation)
 * @param boardId   the board this token grants access to
 * @param shareLink the full invitation URL for the joining user
 * @param role      the role the joining user will receive
 * @param expiresAt when this token expires
 */
public record ShareBoardResponse(
        UUID tokenId,
        UUID boardId,
        String shareLink,
        BoardRole role,
        Instant expiresAt) {

    /**
     * Builds a response from an entity and the plain token.
     *
     * @param token      the persisted token entity
     * @param plainToken the raw token string (not stored — returned once only)
     * @param baseUrl    the frontend base URL used to compose the join link
     * @return the share response
     */
    public static ShareBoardResponse from(
            final BoardShareToken token,
            final String plainToken,
            final String baseUrl) {
        String link = baseUrl + "/whiteboard/join?token=" + plainToken;
        return new ShareBoardResponse(
                token.getId(),
                token.getBoardId(),
                link,
                token.getRole(),
                token.getExpiresAt());
    }
}
