-- V4: vote_session + vote — dot-voting / facilitation session over a board's cards
-- (Vote / dot-voting feature, Klaxoon-style workshop facilitation).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2__whiteboard_parity.sql / V3__card_connection.sql for the precedent this follows):
-- V2 and V3 have already been applied against the real, persistent recette-managed Cloud SQL
-- instance (pivot-project-501905, Cloud Run service pivot-collaboratif-core) by the
-- continuous-deploy pipeline live since 2026-07-14. Flyway has therefore already recorded
-- checksummed V1/V2/V3 rows in that database's flyway_schema_history — editing any of them in
-- place would invalidate its checksum and break Flyway validation on the next deploy. V4 is
-- therefore additive, following the same schema/table conventions established by V1/V2/V3.

-- vote_session: one dot-voting session over a board. At most one ACTIVE session per board at a
-- time — enforced both in application code (VoteActionService, pre-insert check) and at the DB
-- level by the partial unique index below, so a concurrent double-start cannot create two ACTIVE
-- sessions. FK to board ON DELETE CASCADE: deleting a board removes its vote sessions.
-- FK tenant_id -> public.tenants(id): precedent ADR-022 (EN17.4), no ON DELETE CASCADE
-- (public.tenants are soft-deleted / never hard-deleted).
--
-- voter_ids: the eligible-voter allowlist supplied by the facilitator at start (participant user
-- ids), stored as a comma-separated string of BIGINT ids — echoed back to the frontend for the
-- "voterCount" display only. Casting is gated by board membership (WhiteboardChannelInterceptor)
-- and by the per-user quota (votes_per_person), not by this list; it is descriptive, not a
-- security boundary, so a plain TEXT column is deliberately chosen over a normalised join table
-- (keeps the feature to the two tables the spec calls for).
--
-- votes_per_person: the per-participant quota (number of "voices"). timer_seconds / timer_ends_at
-- are both nullable — a session may run without a timer.
CREATE TABLE IF NOT EXISTS collaboratif.vote_session (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id         UUID        NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    tenant_id        BIGINT      NOT NULL REFERENCES public.tenants(id),
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    votes_per_person INTEGER     NOT NULL,
    timer_seconds    INTEGER,
    timer_ends_at    TIMESTAMPTZ,
    voter_ids        TEXT        NOT NULL DEFAULT '',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_vote_session_board   ON collaboratif.vote_session(board_id);
CREATE INDEX IF NOT EXISTS idx_vote_session_tenant  ON collaboratif.vote_session(tenant_id);

-- At most one ACTIVE session per board (partial unique index) — the DB-level backstop for the
-- application's single-active-session invariant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vote_session_active_per_board
    ON collaboratif.vote_session(board_id) WHERE status = 'ACTIVE';

-- vote: a single dot cast by a user on a card within a session. Dot-voting stacks — a user MAY
-- put several dots on the same card — so there is deliberately NO unique(session_id, card_id,
-- user_id) constraint. The anti-oversurvote guarantee (a user never exceeds votes_per_person) is
-- enforced in VoteActionService under a pessimistic row lock on the parent vote_session (a
-- Serializable-equivalent guard for the count-then-insert), not by a SQL uniqueness constraint.
-- FK session_id / card_id both ON DELETE CASCADE: closing/deleting the session or deleting the
-- card removes the associated votes. user_id -> public.users(id) (same convention as
-- board_member.user_id): no ON DELETE CASCADE (users are soft-deleted).
CREATE TABLE IF NOT EXISTS collaboratif.vote (
    id         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id UUID        NOT NULL REFERENCES collaboratif.vote_session(id) ON DELETE CASCADE,
    card_id    UUID        NOT NULL REFERENCES collaboratif.card(id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES public.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_vote_session      ON collaboratif.vote(session_id);
CREATE INDEX IF NOT EXISTS idx_vote_session_user ON collaboratif.vote(session_id, user_id);
CREATE INDEX IF NOT EXISTS idx_vote_card         ON collaboratif.vote(card_id);
