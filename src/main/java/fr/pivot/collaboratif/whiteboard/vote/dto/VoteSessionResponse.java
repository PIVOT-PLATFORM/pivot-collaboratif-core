package fr.pivot.collaboratif.whiteboard.vote.dto;

import fr.pivot.collaboratif.whiteboard.vote.Vote;
import fr.pivot.collaboratif.whiteboard.vote.VoteSession;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link VoteSession} plus its full vote tally.
 *
 * <p>Field names, order, and types match the frontend's {@code VoteSession} interface
 * ({@code board.types.ts}) <strong>exactly</strong> — this is the shape the frontend deserialises
 * both from the STOMP echoes ({@code vote:session:started}, {@code vote:updated},
 * {@code vote:session:closed}) and from the REST reads ({@code GET .../vote/current|last}):
 * <ul>
 *   <li>{@code status} is the enum name ({@code "ACTIVE"}/{@code "CLOSED"}).</li>
 *   <li>{@code timerSeconds} is a nullable integer; {@code timerEndsAt}/{@code closedAt} are
 *       nullable ISO-8601 strings; {@code createdAt} is a non-null ISO-8601 string.</li>
 *   <li>{@code voterIds} is a list of user-id strings; {@code votes} carries every dot cast, so
 *       the client can tally per card and compute each user's own remaining voices — no separate
 *       "remaining" field is sent, matching the frontend type.</li>
 * </ul>
 *
 * @param id             the session UUID as a string
 * @param boardId        the owning board UUID as a string
 * @param status         the lifecycle status name
 * @param votesPerPerson the per-participant quota
 * @param timerSeconds   the timer duration in seconds, or {@code null} for no timer
 * @param timerEndsAt    the timer end instant (ISO-8601), or {@code null} for no timer
 * @param voterIds       the eligible-voter allowlist (user ids as strings)
 * @param votes          every vote cast in the session
 * @param createdAt      the start instant (ISO-8601)
 * @param closedAt       the close instant (ISO-8601), or {@code null} while active
 */
public record VoteSessionResponse(
        String id,
        String boardId,
        String status,
        int votesPerPerson,
        Integer timerSeconds,
        String timerEndsAt,
        List<String> voterIds,
        List<VoteResponse> votes,
        String createdAt,
        String closedAt) {

    /**
     * Builds a {@link VoteSessionResponse} from a session and its votes.
     *
     * @param session the persisted session
     * @param votes   every vote cast in the session (may be empty)
     * @return the corresponding {@link VoteSessionResponse}
     */
    public static VoteSessionResponse of(final VoteSession session, final List<Vote> votes) {
        return new VoteSessionResponse(
                session.getId().toString(),
                session.getBoardId().toString(),
                session.getStatus().name(),
                session.getVotesPerPerson(),
                session.getTimerSeconds(),
                toIso(session.getTimerEndsAt()),
                session.getVoterIds(),
                votes.stream().map(VoteResponse::of).toList(),
                toIso(session.getCreatedAt()),
                toIso(session.getClosedAt()));
    }

    /**
     * Renders an instant to its ISO-8601 string, or {@code null} when the instant is absent.
     *
     * @param instant the instant, or {@code null}
     * @return the ISO-8601 string, or {@code null}
     */
    private static String toIso(final Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
