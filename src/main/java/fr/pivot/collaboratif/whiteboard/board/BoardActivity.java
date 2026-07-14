package fr.pivot.collaboratif.whiteboard.board;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whitelist of known board activity codes that may appear in
 * {@code Board.enabledActivities} (US08.2.4).
 *
 * <p>None of these activities are actually implemented yet in the Socle (Vote, Timer, Session
 * live, etc. are F30.x backlog, out of scope here) — this whitelist exists solely so that
 * {@code PATCH /whiteboard/boards/{boardId}} can validate the {@code enabledActivities}
 * field against a known, closed set rather than accepting arbitrary strings, per the
 * contract ("enabledActivities ⊆ ensemble d'activités connu").
 */
public enum BoardActivity {
    /** Voting activity (F30.x, not implemented in Socle). */
    VOTE,
    /** Timer activity (F30.x, not implemented in Socle). */
    TIMER,
    /** Live facilitation session activity (F30.x, not implemented in Socle). */
    SESSION_LIVE,
    /** Quiz activity (F30.x, not implemented in Socle). */
    QUIZ,
    /** Form activity (F30.x, not implemented in Socle). */
    FORMS;

    /** Machine-readable codes for every known activity, for whitelist membership checks. */
    private static final Set<String> KNOWN_CODES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Checks whether the given code matches a known activity.
     *
     * @param code the activity code to check (case-sensitive, expected upper snake case)
     * @return {@code true} if {@code code} is one of the known activity codes
     */
    public static boolean isKnown(final String code) {
        return KNOWN_CODES.contains(code);
    }
}
