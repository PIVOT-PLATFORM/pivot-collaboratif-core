package fr.pivot.collaboratif.whiteboard.vote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Vote} entities (Vote / dot-voting feature).
 */
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    /**
     * Returns every vote cast in a session, oldest first — used to build the full tally broadcast
     * to the room and returned by the REST read endpoints.
     *
     * @param sessionId the session UUID
     * @return the session's votes; empty if none were cast
     */
    List<Vote> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Counts how many votes a user has already cast in a session — the per-user quota check
     * performed under the session's pessimistic lock before inserting a new vote.
     *
     * @param sessionId the session UUID
     * @param userId    the voting user's {@code public.users.id}
     * @return the number of votes the user currently holds in the session
     */
    long countBySessionIdAndUserId(UUID sessionId, Long userId);

    /**
     * Returns the earliest vote a user holds on a specific card in a session, if any — the single
     * dot removed by {@code vote:uncast} (uncast removes exactly one voice, not every dot on the
     * card).
     *
     * @param sessionId the session UUID
     * @param cardId    the targeted card UUID
     * @param userId    the voting user's {@code public.users.id}
     * @return the oldest matching vote, or empty if the user holds none on that card
     */
    Optional<Vote> findFirstBySessionIdAndCardIdAndUserIdOrderByCreatedAtAsc(
            UUID sessionId, UUID cardId, Long userId);
}
