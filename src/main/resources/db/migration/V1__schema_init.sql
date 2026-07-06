-- V1 unique tant que le schema n'est pas stabilise (convention pivot-core, voir CLAUDE.md :
-- "Migrations Flyway — fichier V1 unique avant la BETA"). Aucune table metier ici : bootstrap
-- uniquement, le developpement des features (whiteboard, quiz, session live, formulaire)
-- commencera par plier ses propres changements de schema dans ce meme fichier jusqu'au feu
-- vert BETA du mainteneur.
CREATE SCHEMA IF NOT EXISTS collaboratif;
