package fr.pivot.collaboratif.whiteboard.canvas.dto;

/**
 * Snapshot of a single participant's presence information on a canvas board.
 *
 * <p>Included in every {@link ParticipantsUpdatePayload} broadcast. The colour is
 * assigned by the server at JOIN time (deterministic by {@code userId}, palette of 12
 * colours — see {@link fr.pivot.collaboratif.whiteboard.canvas.ColorPaletteService}) and
 * remains consistent between the cursor overlay (US08.3.2c) and the presence panel
 * (US08.5.1) for the lifetime of the board session.
 *
 * <p>Security: only {@code userId}, {@code displayName}, {@code role} and {@code color}
 * are exposed — never email or other profile data.
 *
 * @param userId      the participant's {@code public.users.id} as a string
 * @param displayName the display name provided by the client at JOIN
 * @param avatarUrl   optional avatar URL; may be {@code null}
 * @param color       the hex colour assigned by the server (e.g. {@code "#E91E63"})
 * @param role        the participant's board role ({@code OWNER}, {@code EDITOR} or {@code VIEWER})
 */
public record ParticipantInfo(
        String userId,
        String displayName,
        String avatarUrl,
        String color,
        String role) {
}
