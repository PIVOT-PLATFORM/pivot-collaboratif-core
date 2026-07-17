-- US08.2.5: invitation par email + gouvernance des rôles (étend F08.2).
--
-- 1) Identifiant de partage de substitution (shareId) sur board_member.
--    Le modèle BoardShare de la spec correspond à board_member (pas de table dupliquée) : on
--    ajoute une clé de substitution stable et board-indépendante, cible des routes
--    /shares/{shareId}. Elle permet le scoping IDOR `WHERE id = :shareId AND board_id = :boardId`
--    des mutations PATCH/DELETE (fix §6.1 du POC PouetPouet : le POC ne relisait que `role` sans
--    vérifier l'appartenance de la ligne au board du chemin).
ALTER TABLE collaboratif.board_member
    ADD COLUMN IF NOT EXISTS share_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE collaboratif.board_member
    ADD CONSTRAINT uq_board_member_share_id UNIQUE (share_id);

-- 2) Notifications in-app (BOARD_SHARED / ROLE_CHANGED / ACCESS_REVOKED) émises par
--    l'invitation, le changement de rôle et la révocation. Corps de notification en français,
--    libellés de rôle {VIEWER:'lecteur', EDITOR:'éditeur', OWNER:'propriétaire'}.
--    FK vers public.* sans ON DELETE CASCADE (public.users/tenants ne sont jamais supprimés en
--    dur, modèle de désactivation/soft-delete, cf. ADR-022). La suppression d'un board purge en
--    revanche ses notifications (ON DELETE CASCADE sur board_id).
CREATE TABLE IF NOT EXISTS collaboratif.notification (
    id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES public.tenants(id),
    recipient_user_id BIGINT      NOT NULL REFERENCES public.users(id),
    actor_user_id     BIGINT      NOT NULL REFERENCES public.users(id),
    board_id          UUID        REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    type              VARCHAR(30) NOT NULL,
    body              TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at           TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_notification_recipient
    ON collaboratif.notification(recipient_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_tenant
    ON collaboratif.notification(tenant_id);
