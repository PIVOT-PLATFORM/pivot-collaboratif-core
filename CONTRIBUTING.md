# Contributing to PIVOT-CORE

**PIVOT-CORE** est le backend Java/Spring Boot de la suite collaborative PIVOT — API REST, base de données (PostgreSQL + Liquibase), sécurité (Spring Security + JWT/OIDC) et système de modules.
Toute contribution est bienvenue : bug, module, migration BDD, ou documentation.

> Pour contribuer au frontend Angular, voir [pivot-ui](https://github.com/ApoSkunz/pivot-ui).

---

## Table des matières

- [Code de conduite](#code-de-conduite)
- [Ce que nous construisons](#ce-que-nous-construisons)
- [Comment contribuer](#comment-contribuer)
- [Environnement de développement](#environnement-de-développement)
- [Workflow de développement](#workflow-de-développement)
- [Standards de code](#standards-de-code)
- [Soumettre une pull request](#soumettre-une-pull-request)
- [Vulnérabilités de sécurité](#vulnérabilités-de-sécurité)
- [Obtenir de l'aide](#obtenir-de-laide)

---

## Code de conduite

Contributions respectueuses et constructives uniquement.
Pas de harcèlement, discrimination ou engagement de mauvaise foi.
Signalement aux mainteneurs via GitHub private message.

---

## Ce que nous construisons

PIVOT rend accessibles à tous (associations, TPE/PME, entreprises) des outils collaboratifs de qualité :
tableau blanc temps réel, sessions live (quiz, sondage, brainstorm), roadmap/Gantt, quiz gamifié.

Avant de proposer une feature, demande-toi : est-ce que ça aide une équipe à collaborer mieux,
sans dépendre d'un SaaS propriétaire ? Si oui, c'est probablement un bon fit pour PIVOT.

---

## Comment contribuer

### Signaler un bug

Ouvrir une [issue bug](.github/ISSUE_TEMPLATE/bug_report.yml).
Inclure : version PIVOT, plateforme, logs structurés, étapes de reproduction.

### Proposer une fonctionnalité

Ouvrir une [issue feature request](.github/ISSUE_TEMPLATE/feature_request.yml).
Décrire le problème de collaboration résolu — pas juste l'implémentation envisagée.

### Corriger un bug ou implémenter une feature

Voir [Workflow de développement](#workflow-de-développement) ci-dessous.

### Améliorer la documentation

Markdown dans `docs/` et fichiers racine. Aucun outillage spécifique requis.

### Ajouter un module

Les modules PIVOT suivent le contrat `PivotModule`. Ouvrir une issue de discussion avant
de démarrer une implémentation — l'impact sur le contrat de module doit être évalué en amont.

---

## Environnement de développement

Voir [README.md](README.md) — prérequis, démarrage local, SSO OIDC.

---

## Workflow de développement

PIVOT utilise **OneFlow** — branche `main` unique avec branches de feature éphémères.

### 1. Trouver ou créer une issue

Tout travail démarre depuis une GitHub Issue.
Si tu travailles sur une issue existante, commenter pour signaler l'intention.
Si tu proposes du nouveau travail, ouvrir une issue d'abord et attendre l'accord d'un mainteneur
avant d'investir du temps en implémentation.

### 2. Créer une branche

```bash
git checkout main
git pull origin main
git checkout -b feat/us-{issue-id}-{description-courte}
# Exemples :
# feat/us-42-module-survey
# fix/67-oidc-token-refresh
# chore/plumber-config
```

| Préfixe | Usage |
|---------|-------|
| `feat/us-{id}-{slug}` | Nouvelle US |
| `fix/{id}-{slug}` | Correction de bug |
| `refactor/{id}-{slug}` | Refactoring |
| `chore/{slug}` | CI, deps, config |
| `docs/{slug}` | Documentation |

### 3. Implémenter avec couverture des AC

Chaque issue a des critères d'acceptation (AC). L'implémentation doit couvrir chaque AC.
Nommer les fonctions de test avec l'identifiant AC :

```java
// Java
@Test
void ac42_01_createsWhiteboardOnValidRequest() {}
```

```typescript
// Angular
it('AC-42-01: displays error when session not found', () => {});
```

Tests écrits dans le même commit que le code — jamais différés.

### 4. Rebase avant d'ouvrir une PR

```bash
git fetch origin
git rebase -i origin/main
# Squash les commits WIP — chaque commit final doit être lisible
git push --force-with-lease origin feat/us-42-module-survey
```

Pas de merge commits. Pas de commits `wip`, `fix again` ou `test` dans la branche finale.

### 5. Ouvrir une pull request

Utiliser le [template PR](.github/PULL_REQUEST_TEMPLATE.md).
Remplir toutes les sections — notamment la table de traçabilité AC → tests.

---

## Standards de code

Standards complets dans `CLAUDE.md`. Règles clés :

**Java (backend)**
- JavaDoc sur toutes les classes et méthodes publiques
- Checkstyle + SpotBugs doivent être propres
- Pas de logique dans les contrôleurs — déléguer aux services
- DTOs pour toutes les entrées/sorties API — jamais les entités JPA directement
- Pas de `@Transactional` sur les contrôleurs
- Migrations Liquibase versionnées et irréversibles — jamais de `DROP` sans migration compensatoire

**Général**
- Conventional Commits : `type(scope): message`
- Pas de secrets dans le code — variables d'environnement
- `git add` fichier par fichier — jamais `git add .`

---

## Soumettre une pull request

### La CI doit être verte

Tous les checks CI doivent passer avant merge :

1. Qualité — Checkstyle · SpotBugs
2. Tests — JUnit + Testcontainers
3. SonarCloud — Quality Gate ≥ 80 % coverage code nouveau
4. Sécurité — Gitleaks · CodeQL (Java) · Semgrep · Plumber

### Processus de review

- PRs reviewées par les mainteneurs sous 48 h (best effort)
- Changements à impact sécurité → review additionnelle requise
- Changements du contrat de module → PRs coordonnées sur tous les composants impactés
- Label `security` ou `breaking-change` → review humaine obligatoire avant merge

### Co-authorship IA

Si tu as utilisé un assistant IA pour une partie de ta contribution, ajouter :

```
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

Développement IA-assisté transparent — encouragé.

---

## Vulnérabilités de sécurité

**Ne pas ouvrir d'issue publique pour une vulnérabilité de sécurité.**

Signaler via [GitHub Private Vulnerability Reporting](../../security/advisories/new)
ou consulter [SECURITY.md](SECURITY.md) pour la politique de divulgation complète.

---

## Obtenir de l'aide

- **GitHub Discussions** — questions d'architecture, idées de features, discussion générale
- **Issues** — rapports de bugs et demandes de features uniquement
- **GitHub Security Advisories** — vulnérabilités uniquement

Petite équipe — délais de réponse variables mais tout est lu.
