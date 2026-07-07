-- V1 unique tant que le schema n'est pas stabilise (convention pivot-core, voir CLAUDE.md :
-- "Migrations Flyway — fichier V1 unique avant la BETA"). Aucune table metier ici : bootstrap
-- uniquement, le developpement des features (whiteboard, quiz, session live, formulaire)
-- commencera par plier ses propres changements de schema dans ce meme fichier jusqu'au feu
-- vert BETA du mainteneur.
CREATE SCHEMA IF NOT EXISTS collaboratif;

-- US08.1.1: board + board_member
CREATE TABLE IF NOT EXISTS collaboratif.board (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    tenant_id   UUID         NOT NULL,
    owner_id    UUID         NOT NULL,
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_board_tenant_id   ON collaboratif.board(tenant_id);
CREATE INDEX IF NOT EXISTS idx_board_owner_id    ON collaboratif.board(owner_id);
CREATE INDEX IF NOT EXISTS idx_board_updated_at  ON collaboratif.board(updated_at DESC);

CREATE TABLE IF NOT EXISTS collaboratif.board_member (
    board_id  UUID        NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    user_id   UUID        NOT NULL,
    role      VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_board_member_user_id ON collaboratif.board_member(user_id);

-- US08.2.1: board_share_token
CREATE TABLE IF NOT EXISTS collaboratif.board_share_token (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id    UUID         NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    role        VARCHAR(20)  NOT NULL,
    max_uses    INTEGER      NOT NULL DEFAULT 1,
    use_count   INTEGER      NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_share_token_board_id ON collaboratif.board_share_token(board_id);
CREATE INDEX IF NOT EXISTS idx_share_token_hash     ON collaboratif.board_share_token(token_hash);
