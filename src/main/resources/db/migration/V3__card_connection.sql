-- V3: card_connection — connector linking two cards on the same board (US08.7.1).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and
-- V2__whiteboard_parity.sql's own header for the precedent this follows): V2 was itself already
-- deployed against the real, persistent recette-managed Cloud SQL instance
-- (pivot-project-501905, Cloud Run service pivot-collaboratif-core) by the continuous-deploy
-- pipeline confirmed live since 2026-07-14 — several PRs have merged to main since
-- V2__whiteboard_parity.sql landed on 2026-07-13 (favoris/corbeille/recherche/paramètres, CI
-- deploy wiring), so Flyway has almost certainly already recorded a checksummed V2 entry in
-- that database's flyway_schema_history. Editing V2 in place would risk invalidating that
-- checksum and breaking Flyway validation on the next deploy — the exact same reasoning V2's
-- own header used to justify not editing V1 in place. V3 is therefore additive, following the
-- same schema/table conventions established by V1/V2.

-- US08.7.1: card_connection — connector (curve/line) linking two cards on the same board.
-- FK to card ON DELETE CASCADE on both ends: deleting either endpoint card removes the
-- connector, so the connection:delete STOMP handler must tolerate an id already gone via
-- cascade (see CanvasActionService#handleConnectionDelete). Defaults match the parity spec
-- §7 exactly (shape=curved, arrow=none, dashed=false, width=2); label/color start null.
-- Anti-duplicate (bidirectional) and anti-self-link are enforced in application code
-- (CanvasActionService), not by a SQL constraint — see the US's "Hors périmètre" section.
CREATE TABLE IF NOT EXISTS collaboratif.card_connection (
    id         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id   UUID        NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    tenant_id  BIGINT      NOT NULL REFERENCES public.tenants(id),
    from_id    UUID        NOT NULL REFERENCES collaboratif.card(id) ON DELETE CASCADE,
    to_id      UUID        NOT NULL REFERENCES collaboratif.card(id) ON DELETE CASCADE,
    label      TEXT,
    color      VARCHAR(20),
    shape      VARCHAR(20) NOT NULL DEFAULT 'curved',
    arrow      VARCHAR(20) NOT NULL DEFAULT 'none',
    dashed     BOOLEAN     NOT NULL DEFAULT false,
    width      INTEGER     NOT NULL DEFAULT 2,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_card_connection_board ON collaboratif.card_connection(board_id);
CREATE INDEX IF NOT EXISTS idx_card_connection_from  ON collaboratif.card_connection(from_id);
CREATE INDEX IF NOT EXISTS idx_card_connection_to    ON collaboratif.card_connection(to_id);
