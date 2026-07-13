-- V2: whiteboard "visible parity" additive schema changes (US08.1.6/7/8, US08.2.4).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA"):
-- the default rule before BETA is to fold every schema change back into V1__schema_init.sql.
-- This migration deliberately deviates from that default because a real, persistent
-- recette-managed Cloud SQL instance (pivot-project-501905, Cloud Run service
-- pivot-collaboratif-core, revision pivot-collaboratif-core-00001-fld/00002-hhw) already
-- started successfully against this schema on 2026-07-13 (see Cloud Run logs: "Started
-- PivotCollaboratifApplication in 30.6 seconds") — meaning Flyway already recorded a
-- checksummed V1 entry in that database's flyway_schema_history. Editing V1 in place would
-- invalidate that checksum and break Flyway validation on the next deploy to that instance.
-- V2 is therefore purely additive, following the same schema/table conventions as V1.

-- US08.1.8: accent-insensitive search (title/description) needs the unaccent() function.
-- Standard PostgreSQL contrib extension, available on Cloud SQL for PostgreSQL without
-- extra flags/superuser grants (allow-listed extension). Installed explicitly into the
-- shared `public` schema so the function resolves regardless of the connection search_path
-- (Flyway/Hibernate both run with default_schema=collaboratif, which would otherwise place
-- and hide the function inside `collaboratif`). The query references it as `public.unaccent`.
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;

-- US08.1.7: soft-delete support on board.
ALTER TABLE collaboratif.board
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_board_deleted_at ON collaboratif.board(deleted_at);

-- US08.2.4: board settings (description, cover image, participant cap, enabled activities).
ALTER TABLE collaboratif.board
    ADD COLUMN IF NOT EXISTS description        TEXT,
    ADD COLUMN IF NOT EXISTS cover_image         TEXT,
    ADD COLUMN IF NOT EXISTS max_participants     INTEGER,
    ADD COLUMN IF NOT EXISTS enabled_activities   TEXT[] NOT NULL DEFAULT '{}';

-- US08.1.6: per-user favorite flag on a board. Strictly personal (never shared/visible to
-- other members) — composite PK (board_id, user_id), one row per (board, user) toggle-on.
CREATE TABLE IF NOT EXISTS collaboratif.board_favorite (
    board_id   UUID        NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES public.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_board_favorite_user_id ON collaboratif.board_favorite(user_id);
