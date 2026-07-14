package fr.pivot.collaboratif.whiteboard.board.dto;

import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing a whiteboard board visible to the caller.
 *
 * <p>The {@code role} field reflects the caller's role on this board (e.g. {@code "OWNER"},
 * {@code "EDITOR"}, {@code "VIEWER"} — {@link BoardRole#name()}, matching {@code MemberResponse}
 * and the WS JOIN broadcast's casing, see {@code CanvasActionService#resolveRoleName}). After
 * creation, it is always {@code "OWNER"}.
 *
 * <p>Fields {@code thumbnailUrl} and {@code activeParticipantCount} are stubs for
 * future integrations (thumbnail storage and WebSocket presence registry respectively).
 *
 * <p>{@code favorite} reflects whether the calling user has favorited this board
 * (US08.1.6, strictly personal — never derived from another user's favorite). {@code
 * description}, {@code coverImage}, {@code maxParticipants}, and {@code enabledActivities}
 * are the board settings introduced by US08.2.4. {@code deletedAt} is only meaningfully
 * populated when the board is returned as part of the trash listing (US08.1.7) — it is
 * {@code null} in every other response (normal list/get), even though the field is always
 * present in the JSON shape for a stable contract.
 *
 * @param id                     unique identifier of the board
 * @param title                  human-readable board title
 * @param role                   the caller's role on this board ({@link BoardRole#name()})
 * @param createdAt              timestamp when the board was created
 * @param updatedAt              timestamp of the last board update
 * @param tenantId               {@code public.tenants.id} of the tenant that owns this board
 * @param thumbnailUrl           URL of the board thumbnail image, or {@code null} in Socle
 * @param activeParticipantCount number of users currently active on the board
 * @param favorite               {@code true} if the calling user has favorited this board
 * @param description            optional board description, or {@code null}
 * @param coverImage             optional cover image URL, or {@code null}
 * @param maxParticipants        optional maximum simultaneous participant count, or
 *                               {@code null} for unlimited
 * @param enabledActivities      whitelisted activity codes enabled on this board
 * @param deletedAt              trash timestamp, populated only in trash listings, otherwise
 *                               {@code null}
 */
public record BoardResponse(
        UUID id,
        String title,
        String role,
        Instant createdAt,
        Instant updatedAt,
        Long tenantId,
        String thumbnailUrl,
        int activeParticipantCount,
        boolean favorite,
        String description,
        String coverImage,
        Integer maxParticipants,
        List<String> enabledActivities,
        Instant deletedAt) {

    /**
     * Creates a {@link BoardResponse} from a {@link Board} entity and the caller's resolved
     * {@link BoardRole}, outside of a trash listing context ({@code deletedAt} always
     * {@code null} in the response).
     *
     * @param board      the board entity
     * @param callerRole the role the calling user holds on this board
     * @param favorite   {@code true} if the calling user has favorited this board
     * @return a populated response record
     */
    public static BoardResponse from(
            final Board board, final BoardRole callerRole, final boolean favorite) {
        return build(board, callerRole, favorite, false);
    }

    /**
     * Creates a {@link BoardResponse} from a {@link Board} entity for the trash listing
     * (US08.1.7), where {@code deletedAt} is populated from the entity.
     *
     * @param board      the trashed board entity
     * @param callerRole the role the calling user holds on this board (always OWNER, since
     *                   only the owner may list the trash)
     * @return a populated response record with {@code deletedAt} set
     */
    public static BoardResponse forTrash(final Board board, final BoardRole callerRole) {
        return build(board, callerRole, false, true);
    }

    /**
     * Backward-compatible factory without a favorite flag (defaults to {@code false}) —
     * kept only for call sites that do not (yet) resolve the caller's favorite state.
     *
     * @param board      the board entity
     * @param callerRole the role the calling user holds on this board
     * @return a populated response record with {@code favorite=false}
     */
    public static BoardResponse from(final Board board, final BoardRole callerRole) {
        return from(board, callerRole, false);
    }

    private static BoardResponse build(
            final Board board,
            final BoardRole callerRole,
            final boolean favorite,
            final boolean includeDeletedAt) {
        return new BoardResponse(
                board.getId(),
                board.getTitle(),
                callerRole.name(),
                board.getCreatedAt(),
                board.getUpdatedAt(),
                board.getTenantId(),
                null,  // thumbnailUrl: null in Socle
                0,     // TODO: wire to WS presence registry (EN08.1)
                favorite,
                board.getDescription(),
                board.getCoverImage(),
                board.getMaxParticipants(),
                List.copyOf(board.getEnabledActivities()),
                includeDeletedAt ? board.getDeletedAt() : null);
    }
}
