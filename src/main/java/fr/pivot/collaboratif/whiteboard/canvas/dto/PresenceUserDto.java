package fr.pivot.collaboratif.whiteboard.canvas.dto;

/**
 * Wire representation of one present participant, broadcast as an array under the
 * {@code board:presence} type on a board's main topic ({@code /topic/whiteboard/{boardId}}).
 *
 * <p>Mirrors the frontend's {@code PresenceUser} shape exactly ({@code board.types.ts}:
 * {@code { id, name, avatar }}) — the store's {@code this.on<PresenceUser[]>('board:presence', …)}
 * handler drives the presence panel and prunes stale cursors by {@code id}. Distinct from
 * {@link ParticipantInfo} (which additionally carries {@code color}/{@code role} for the
 * {@code /presence} sub-topic's {@link ParticipantsUpdatePayload}); this projection exposes only
 * the three fields the {@code board:presence} consumer reads.
 *
 * @param id     the participant's {@code public.users.id} as a string
 * @param name   the display name provided at JOIN
 * @param avatar optional avatar URL; may be {@code null}
 */
public record PresenceUserDto(String id, String name, String avatar) {

    /**
     * Projects a {@link ParticipantInfo} onto the {@code board:presence} wire shape.
     *
     * @param info the fuller participant record held in the presence store
     * @return the {@code {id, name, avatar}} projection
     */
    public static PresenceUserDto from(final ParticipantInfo info) {
        return new PresenceUserDto(info.userId(), info.displayName(), info.avatarUrl());
    }
}
