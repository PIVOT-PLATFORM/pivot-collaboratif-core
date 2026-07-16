-- US08.10.1: board_field + card_field_value — custom, board-level typed fields ("champs core")
-- that a board OWNER/EDITOR can define on a board, plus the per-card value table US08.10.2 will
-- populate. Same durable current-state model as collaboratif.card / collaboratif.frame (V1):
-- one row per field, created/updated/deleted exclusively over STOMP (boardfield:* actions, see
-- CanvasActionService) — no dedicated REST route, mirroring cards and frames. The maintainer
-- explicitly approved this V5 file (beyond the V1-unique convention) for US08.10.1.

-- board_field: one custom field of a board. type in ('TEXT','NUMBER','DATE','SELECT') — the same
-- whitelist enforced in code by FieldType.fromWire (an invalid type is dropped before persist,
-- never inserted, §6.6 fix). field_order (not `order`, a SQL reserved word) drives display order.
-- options holds a JSON array of allowed values for a SELECT field, NULL otherwise.
CREATE TABLE IF NOT EXISTS collaboratif.board_field (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id    UUID        NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    tenant_id   BIGINT      NOT NULL,
    name        TEXT        NOT NULL,
    emoji       VARCHAR(32),
    type        VARCHAR(20) NOT NULL,
    options     JSONB,
    field_order INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_board_field_board
    ON collaboratif.board_field(board_id, field_order ASC, created_at ASC);

-- card_field_value: one card's value for one board_field, unique per (card, field). Both FKs
-- cascade — deleting a card (board reset, card:delete) or a board_field (boardfield:delete)
-- removes the associated values automatically. Created now so the boardfield:delete cascade works
-- and US08.10.2 can build its set/clear handlers on top of this table.
CREATE TABLE IF NOT EXISTS collaboratif.card_field_value (
    id       UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    card_id  UUID NOT NULL REFERENCES collaboratif.card(id) ON DELETE CASCADE,
    field_id UUID NOT NULL REFERENCES collaboratif.board_field(id) ON DELETE CASCADE,
    value    TEXT NOT NULL,
    UNIQUE (card_id, field_id)
);
CREATE INDEX IF NOT EXISTS idx_card_field_value_field ON collaboratif.card_field_value(field_id);
