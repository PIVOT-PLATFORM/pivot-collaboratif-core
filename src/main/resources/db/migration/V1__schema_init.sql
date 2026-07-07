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

-- US08.3.1: canvas_event (DRAW persistence — Last-Write-Wins, event-sourcing approach)
CREATE TABLE IF NOT EXISTS collaboratif.canvas_event (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id    UUID         NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    tenant_id   UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    event_type  VARCHAR(20)  NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_canvas_event_board ON collaboratif.canvas_event(board_id, created_at ASC);

-- US08.4.1: whiteboard_template + whiteboard_template_element
-- tenant_id nullable: NULL = global public template. Resolution Gate 1 (pivot-docs,
-- us-tableau-depuis-template.md): in Socle only global templates exist, no tenant-scoped
-- template creation channel is exposed, so no row with a non-null tenant_id is ever produced
-- here. The column stays nullable to remain extensible without a breaking migration once
-- US30.4.2 (phase-3) unlocks tenant-owned templates.
CREATE TABLE IF NOT EXISTS collaboratif.whiteboard_template (
    id            UUID         NOT NULL PRIMARY KEY,
    tenant_id     UUID,
    code          VARCHAR(50)  NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    thumbnail_url VARCHAR(255),
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_whiteboard_template_tenant_id ON collaboratif.whiteboard_template(tenant_id);

-- element_type in ('SHAPE', 'TEXT', 'IMAGE') — same strict whitelist enforced in code by
-- CanvasElementValidator at board-initialization time (defense in depth on seed data).
CREATE TABLE IF NOT EXISTS collaboratif.whiteboard_template_element (
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    template_id   UUID         NOT NULL REFERENCES collaboratif.whiteboard_template(id) ON DELETE CASCADE,
    element_type  VARCHAR(20)  NOT NULL,
    payload       JSONB        NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_template_element_template
    ON collaboratif.whiteboard_template_element(template_id, display_order ASC);

-- Seed data: the 3 initial global templates (Brainstorm, Retrospective, User Story Map).
-- "Vierge" (blank) is intentionally NOT seeded here — it is covered by plain POST
-- /whiteboard/boards without a templateId (US08.1.1), per this US's acceptance criteria.
INSERT INTO collaboratif.whiteboard_template
    (id, tenant_id, code, name, description, thumbnail_url, display_order)
VALUES
    ('11111111-1111-1111-1111-111111111111', NULL, 'BRAINSTORM', 'Brainstorm',
     'Espace libre pour capturer des idees en vrac avant de les organiser.',
     '/assets/templates/brainstorm.png', 0),
    ('22222222-2222-2222-2222-222222222222', NULL, 'RETROSPECTIVE', 'Retrospective',
     'Colonnes Bien, A ameliorer, Actions pour animer une retrospective en equipe.',
     '/assets/templates/retrospective.png', 1),
    ('33333333-3333-3333-3333-333333333333', NULL, 'USER_STORY_MAP', 'User Story Map',
     'Grille pour cartographier un parcours utilisateur en epics et releases.',
     '/assets/templates/user-story-map.png', 2)
ON CONFLICT (id) DO NOTHING;

-- Brainstorm elements: title + two idea sticky notes
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, payload, display_order)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'TEXT',
     '{"x":40,"y":20,"width":400,"height":50,"content":"Brainstorm","fontSize":28,"color":"#1F2937"}',
     0),
    ('11111111-1111-1111-1111-111111111111', 'SHAPE',
     '{"x":40,"y":100,"width":220,"height":150,"shapeKind":"rectangle","color":"#FEF08A","strokeWidth":1}',
     1),
    ('11111111-1111-1111-1111-111111111111', 'TEXT',
     '{"x":60,"y":150,"width":180,"height":60,"content":"Idee 1","fontSize":16,"color":"#1F2937"}',
     2),
    ('11111111-1111-1111-1111-111111111111', 'SHAPE',
     '{"x":300,"y":100,"width":220,"height":150,"shapeKind":"rectangle","color":"#BBF7D0","strokeWidth":1}',
     3),
    ('11111111-1111-1111-1111-111111111111', 'TEXT',
     '{"x":320,"y":150,"width":180,"height":60,"content":"Idee 2","fontSize":16,"color":"#1F2937"}',
     4);

-- Retrospective elements: title + 3 columns (Bien / A ameliorer / Actions)
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, payload, display_order)
VALUES
    ('22222222-2222-2222-2222-222222222222', 'TEXT',
     '{"x":40,"y":20,"width":600,"height":50,"content":"Retrospective","fontSize":28,"color":"#1F2937"}',
     0),
    ('22222222-2222-2222-2222-222222222222', 'SHAPE',
     '{"x":40,"y":100,"width":260,"height":400,"shapeKind":"rectangle","color":"#DCFCE7","strokeWidth":1}',
     1),
    ('22222222-2222-2222-2222-222222222222', 'TEXT',
     '{"x":60,"y":120,"width":220,"height":40,"content":"Bien","fontSize":18,"color":"#166534"}',
     2),
    ('22222222-2222-2222-2222-222222222222', 'SHAPE',
     '{"x":320,"y":100,"width":260,"height":400,"shapeKind":"rectangle","color":"#FEE2E2","strokeWidth":1}',
     3),
    ('22222222-2222-2222-2222-222222222222', 'TEXT',
     '{"x":340,"y":120,"width":220,"height":40,"content":"A ameliorer","fontSize":18,"color":"#991B1B"}',
     4),
    ('22222222-2222-2222-2222-222222222222', 'SHAPE',
     '{"x":600,"y":100,"width":260,"height":400,"shapeKind":"rectangle","color":"#DBEAFE","strokeWidth":1}',
     5),
    ('22222222-2222-2222-2222-222222222222', 'TEXT',
     '{"x":620,"y":120,"width":220,"height":40,"content":"Actions","fontSize":18,"color":"#1E3A8A"}',
     6);

-- User Story Map elements: title + legend icon + 2 epics + 1 release row
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, payload, display_order)
VALUES
    ('33333333-3333-3333-3333-333333333333', 'TEXT',
     '{"x":40,"y":20,"width":600,"height":50,"content":"User Story Map","fontSize":28,"color":"#1F2937"}',
     0),
    ('33333333-3333-3333-3333-333333333333', 'IMAGE',
     '{"x":650,"y":20,"width":32,"height":32,"url":"/assets/templates/icon-legend.svg","altText":"Legende de la carte utilisateur"}',
     1),
    ('33333333-3333-3333-3333-333333333333', 'SHAPE',
     '{"x":40,"y":100,"width":200,"height":80,"shapeKind":"rectangle","color":"#E0E7FF","strokeWidth":1}',
     2),
    ('33333333-3333-3333-3333-333333333333', 'TEXT',
     '{"x":60,"y":120,"width":160,"height":40,"content":"Epic 1","fontSize":16,"color":"#1F2937"}',
     3),
    ('33333333-3333-3333-3333-333333333333', 'SHAPE',
     '{"x":260,"y":100,"width":200,"height":80,"shapeKind":"rectangle","color":"#E0E7FF","strokeWidth":1}',
     4),
    ('33333333-3333-3333-3333-333333333333', 'TEXT',
     '{"x":280,"y":120,"width":160,"height":40,"content":"Epic 2","fontSize":16,"color":"#1F2937"}',
     5),
    ('33333333-3333-3333-3333-333333333333', 'SHAPE',
     '{"x":40,"y":200,"width":420,"height":100,"shapeKind":"rectangle","color":"#F1F5F9","strokeWidth":1}',
     6),
    ('33333333-3333-3333-3333-333333333333', 'TEXT',
     '{"x":60,"y":220,"width":380,"height":40,"content":"Release 1","fontSize":16,"color":"#1F2937"}',
     7);
