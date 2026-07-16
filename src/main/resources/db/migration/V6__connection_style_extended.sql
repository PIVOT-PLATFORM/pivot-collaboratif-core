-- V6: card_connection style extension — line style + head/tail caps (US08.7.2 style extension).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and
-- V3__card_connection.sql's own header for the precedent this follows): every prior V* migration
-- (V1..V5) has already been deployed against the real, persistent recette-managed Cloud SQL
-- instance by the continuous-deploy pipeline live since 2026-07-14, so Flyway has almost certainly
-- recorded checksummed entries for them in that database's flyway_schema_history. Editing any of
-- them in place would risk invalidating those checksums and breaking Flyway validation on the next
-- deploy. V6 is therefore additive, following the same schema/table conventions as V1..V5.

-- US08.7.2 style extension: extend card_connection styling with an explicit line style
-- (solid/dashed/dotted, superseding the boolean `dashed`) and per-end head caps
-- (none/arrow/triangle/circle/diamond, superseding the legacy `arrow`). The legacy `dashed`/`arrow`
-- columns are kept for back-compat and derived-in-sync on write by CanvasActionService/
-- CardConnection. Value whitelisting is enforced in application code (CanvasActionService), not by
-- a SQL CHECK constraint — same convention as shape/arrow (see V3's "Hors périmètre").
ALTER TABLE collaboratif.card_connection
    ADD COLUMN IF NOT EXISTS line_style VARCHAR(20) NOT NULL DEFAULT 'solid',
    ADD COLUMN IF NOT EXISTS start_cap  VARCHAR(20) NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS end_cap    VARCHAR(20) NOT NULL DEFAULT 'none';

-- Backfill existing rows from the legacy dashed/arrow columns so the new fields are consistent
-- with any styling applied before this migration:
--   line_style: dashed=true → 'dashed', else the 'solid' default already applied above.
--   start_cap / end_cap: an 'arrow' cap on the end(s) implied by the legacy arrow value.
UPDATE collaboratif.card_connection SET line_style = 'dashed' WHERE dashed = true;
UPDATE collaboratif.card_connection SET start_cap = 'arrow' WHERE arrow IN ('start', 'both');
UPDATE collaboratif.card_connection SET end_cap   = 'arrow' WHERE arrow IN ('end', 'both');
