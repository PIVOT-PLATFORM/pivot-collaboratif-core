package fr.pivot.collaboratif.whiteboard.vote;

/**
 * Lifecycle status of a {@link VoteSession}.
 *
 * <p>A session is created {@link #ACTIVE} on {@code vote:start} and transitions once, terminally,
 * to {@link #CLOSED} on {@code vote:stop} (or when its timer elapses, handled client-side). There
 * is no reopening — a new vote is a new session. The wire form is the enum name itself
 * ({@code "ACTIVE"}/{@code "CLOSED"}), matching the frontend's {@code VoteSession.status} union
 * type exactly.
 */
public enum VoteStatus {

    /** The session accepts casts and uncasts. At most one ACTIVE session exists per board. */
    ACTIVE,

    /** The session is terminated; no further casts/uncasts are accepted. */
    CLOSED
}
