-- V6: card_connection.line_style / start_cap / end_cap — extended connector styling (US08.7.2).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2/V3/V4/V5 for the precedent this follows): V1→V5 have already been applied against
-- the real, persistent recette-managed Cloud SQL instance by the continuous-deploy pipeline —
-- Flyway has recorded their checksums, so editing any of them in place would break validation on
-- the next deploy. V6 is therefore additive. The maintainer explicitly approved this V6 file
-- (beyond the V1-unique convention) for the extended connector styling of US08.7.2.
--
-- Why these columns rather than reusing `dashed`/`arrow`: the existing model cannot express what
-- the feature needs. `dashed BOOLEAN` has no room for a third line style (dotted), and `arrow`
-- carries one value for both ends at once, so it can neither give the two ends different shapes
-- nor say "triangle" instead of "arrow". The frontend sends these fields on connection:create and
-- connection:update; without the columns the server would silently drop them (the patch helper
-- only applies fields it knows), and — the board being server-authoritative with no optimistic
-- rendering — the user's chosen style would simply never appear.
--
-- `dashed`/`arrow` are deliberately KEPT, not dropped: they are the fields every already-stored
-- connector carries, and older clients still send them. They stay the source of truth for those,
-- while the columns below take over for clients that know about them.
ALTER TABLE collaboratif.card_connection
    ADD COLUMN IF NOT EXISTS line_style VARCHAR(20) NOT NULL DEFAULT 'solid',
    ADD COLUMN IF NOT EXISTS start_cap  VARCHAR(20) NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS end_cap    VARCHAR(20) NOT NULL DEFAULT 'none';

-- Back-fill from the legacy fields so every connector already on a board keeps the exact look it
-- has today: a dashed one stays dashed, and an arrow head stays on the same end(s) it was on.
-- Without this the defaults above would silently flatten every existing connector to a plain solid
-- line with no arrow — a visible, unannounced change on boards people already use.
UPDATE collaboratif.card_connection
   SET line_style = CASE WHEN dashed THEN 'dashed' ELSE 'solid' END,
       start_cap  = CASE WHEN arrow IN ('start', 'both') THEN 'arrow' ELSE 'none' END,
       end_cap    = CASE WHEN arrow IN ('end', 'both') THEN 'arrow' ELSE 'none' END;
