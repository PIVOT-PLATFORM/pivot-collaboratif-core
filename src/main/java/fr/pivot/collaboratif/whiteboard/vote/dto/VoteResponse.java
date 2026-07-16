package fr.pivot.collaboratif.whiteboard.vote.dto;

import fr.pivot.collaboratif.whiteboard.vote.Vote;

/**
 * Wire representation of a single {@link Vote}, nested inside {@link VoteSessionResponse#votes()}.
 *
 * <p>Field names and types match the frontend's {@code BoardVote} interface
 * ({@code board.types.ts}) exactly: every id and the timestamp are strings. Deliberately not the
 * JPA entity itself — entities are never serialised directly to clients.
 *
 * @param id        the vote's UUID as a string
 * @param sessionId the owning session's UUID as a string
 * @param cardId    the targeted card's UUID as a string
 * @param userId    the voting user's {@code public.users.id} as a string
 * @param createdAt the cast instant, ISO-8601
 */
public record VoteResponse(
        String id,
        String sessionId,
        String cardId,
        String userId,
        String createdAt) {

    /**
     * Builds a {@link VoteResponse} from a persisted vote.
     *
     * @param vote the persisted vote
     * @return the corresponding {@link VoteResponse}
     */
    public static VoteResponse of(final Vote vote) {
        return new VoteResponse(
                vote.getId().toString(),
                vote.getSessionId().toString(),
                vote.getCardId().toString(),
                String.valueOf(vote.getUserId()),
                vote.getCreatedAt().toString());
    }
}
