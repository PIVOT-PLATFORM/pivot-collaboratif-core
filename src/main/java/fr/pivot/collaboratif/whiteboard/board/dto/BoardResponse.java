package fr.pivot.collaboratif.whiteboard.board.dto;

import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload representing a whiteboard board visible to the caller.
 *
 * <p>The {@code role} field reflects the caller's role on this board (e.g. {@code "owner"},
 * {@code "editor"}, {@code "viewer"}). After creation, it is always {@code "owner"}.
 *
 * <p>Fields {@code thumbnailUrl} and {@code activeParticipantCount} are stubs for
 * future integrations (thumbnail storage and WebSocket presence registry respectively).
 *
 * @param id                     unique identifier of the board
 * @param title                  human-readable board title
 * @param role                   the caller's role on this board (lowercase)
 * @param createdAt              timestamp when the board was created
 * @param updatedAt              timestamp of the last board update
 * @param tenantId               tenant that owns this board
 * @param thumbnailUrl           URL of the board thumbnail image, or {@code null} in Socle
 * @param activeParticipantCount number of users currently active on the board
 */
public record BoardResponse(
        UUID id,
        String title,
        String role,
        Instant createdAt,
        Instant updatedAt,
        UUID tenantId,
        String thumbnailUrl,
        int activeParticipantCount) {

    /**
     * Factory method that creates a {@link BoardResponse} from a {@link Board} entity
     * and the caller's resolved {@link BoardRole}.
     *
     * @param board      the board entity
     * @param callerRole the role the calling user holds on this board
     * @return a populated response record
     */
    public static BoardResponse from(final Board board, final BoardRole callerRole) {
        return new BoardResponse(
                board.getId(),
                board.getTitle(),
                callerRole.name().toLowerCase(),
                board.getCreatedAt(),
                board.getUpdatedAt(),
                board.getTenantId(),
                null,  // thumbnailUrl: null in Socle
                0);    // TODO: wire to WS presence registry (EN08.1)
    }
}
