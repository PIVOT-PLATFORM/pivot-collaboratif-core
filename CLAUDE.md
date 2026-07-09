# CLAUDE.md — PIVOT-COLLABORATIF-CORE

## Projet

**PIVOT-COLLABORATIF-CORE** — backend Java/Spring Boot du domaine **Collaboratif** de la suite
PIVOT : whiteboard collaboratif temps réel, quiz interactif, session live (facilitation
d'atelier), formulaire. Repo module dédié dans l'architecture multi-repo PIVOT — voir backlog
`pivot-docs/docs/backlog/EPIC-collaboration/README.md` (EPIC **E30**, dont le noyau whiteboard
F08.x/EN08.x — ex-`EPIC-whiteboard`/E08, fusionné dans E30 — garde sa propre `Phase: Socle` non
verrouillée).

**Statut actuel.** Le noyau whiteboard (F08.x/EN08.x) est implémenté : création/liste/
renommage/suppression de tableaux, partage par lien avec rôles, canvas temps réel WebSocket
STOMP, templates. 9+ PR de feature mergées depuis le bootstrap initial. L'authentification
réelle (EN08.3) est branchée depuis `fr.pivot:pivot-core-starter` — voir section
"Authentification" plus bas.

Le frontend Angular associé est **pivot-collaboratif-ui**. Le backend shell (auth, tenant,
équipes, registre modules) est **pivot-core**. La documentation générale et le backlog vivent
dans **pivot-docs**.

### Dépendance `pivot-core-starter` — état réel

`pivot-core` publie réellement `fr.pivot:pivot-core-starter` depuis la version **0.27.0**
(EN17.1, `pivot-core#171`/`#173`/`#176`/`#177`/`#180`, ADR-022) sur GitHub Packages du repo
`pivot-core`. Ce repo en dépend (`pom.xml`, version épinglée explicitement — jamais devinée) pour
`fr.pivot.core.auth.AuthenticatedPrincipal`/`AuthenticatedPrincipalResolver`, consommé par
`fr.pivot.collaboratif.auth.TokenValidationService` (EN08.3). **Épinglé à 0.27.1** : 0.27.0
déclarait un `<parent>` (`fr.pivot:pivot-core:0.27.0`) jamais publié comme POM d'agrégateur
racine sur GitHub Packages, rendant la résolution impossible pour tout consommateur externe —
corrigé côté `pivot-core` en 0.27.1 (publication du POM racine + suivi de version cohérent entre
la racine et ses sous-modules).

Packages restants du starter (`fr.pivot.core.tenant`, `fr.pivot.core.team`,
`fr.pivot.core.modules`, `fr.pivot.core.db`) : pas encore consommés ici — `PivotModule` et le
registre de modules restent à implémenter quand une US le spécifie explicitement.

**Toujours épingler une version réelle et publiée** — vérifier l'état de publication
(`pivot-core/pom.xml`, `pivot-core/.github/workflows/release.yml`) avant toute mise à jour de
version, jamais une version devinée.

---

## Communication

Concise et directe. Techniquement précise. Pas de récapitulatifs inutiles.

**Exceptions (réponses complètes et structurées) :**
- Rédaction ou revue d'US / Epics
- Décisions d'architecture (schéma BDD, isolation WebSocket, dépendance pivot-core-starter)
- Avis cybersécurité ou actions irréversibles — **confirmation obligatoire**
- Backlog et critères d'acceptation

---

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 25 · Spring Boot 4.x · Maven · `--release 24` (pas de preview features) |
| BDD | PostgreSQL 18 (instance **partagée**, schéma dédié `collaboratif`) · Spring Data JPA · Flyway |
| Cache | Redis (instance partagée avec les autres modules) |
| Temps réel | WebSocket STOMP — **branché** (`WebSocketConfig`, endpoint `/ws/whiteboard`) : `SimpleBroker` en mémoire pour `/topic/whiteboard/*` (isolation par room de board, `EN08.1`), présence, rate limiting (`US08.3.1`). EN07.3 ajoute un relai STOMP additif vers **ActiveMQ** (`:61613`, `/topic/collaboratif.*`) pour le futur bus d'événements inter-domaines — rien n'y publie encore. Handshake authentifié par bearer token (EN08.3, voir section "Authentification"). |
| Auth | Bearer opaque token `pivot-core`, validé directement contre `public.access_tokens`/`users`/`tenants` via `fr.pivot:pivot-core-starter` (EN08.3, ADR-022 — validation dupliquée localement, jamais d'appel réseau). Voir section "Authentification" plus bas. |
| Tests | JUnit 5 · Mockito · Testcontainers (PostgreSQL, Redis) |
| Observabilité | Spring Actuator · Micrometer · Prometheus |
| CI/CD | GitHub Actions · SonarCloud (à finaliser côté secrets, voir `TODO-SETUP.md`) · Semantic Release · Plumber |
| Déploiement | Docker |
| Frontend | → **pivot-collaboratif-ui** (Angular 22 · `@stomp/rx-stomp`) |

---

## Structure du dépôt

```
pivot-collaboratif-core/
├── src/
│   ├── main/java/fr/pivot/collaboratif/
│   │   └── PivotCollaboratifApplication.java   # Point d'entrée — aucune feature encore
│   ├── main/resources/
│   │   ├── application.yml                     # Port 8083 · schéma collaboratif · Redis partagé
│   │   ├── application-test.yml
│   │   └── db/migration/                        # V1 unique — voir règle ci-dessous
│   └── test/java/fr/pivot/collaboratif/
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── .plumber.yaml
├── TODO-SETUP.md         # Setup manuel restant (SonarCloud, secrets, ruleset stricte)
└── Dockerfile
```

**Maven :** projet single-module `fr.pivot:pivot-collaboratif-core`.

**Migrations Flyway — fichier V1 unique avant la BETA :** même convention que `pivot-core` — tant
que le schéma n'est pas stabilisé, tout changement de schéma est plié dans l'unique
`V1__schema_init.sql` plutôt que d'ajouter un `V2__`/`V3__…` séparé. Ne pas créer de nouveau
fichier de migration numéroté sans feu vert explicite du mainteneur (déclenché au démarrage de
la BETA).

**Contexte HTTP :** `server.servlet.context-path: /api/collaboratif` — nginx route
`/api/collaboratif/**` vers ce service **sans réécriture de chemin** (`proxy_pass` sans chemin
final), le context-path doit donc reprendre exactement ce préfixe côté JVM. Idem WebSocket :
endpoint STOMP enregistré à `/ws/whiteboard` (`WebSocketConfig`), soit
`/api/collaboratif/ws/whiteboard` en URL complète.

Frontend Angular → **pivot-collaboratif-ui**. Documentation/backlog → **pivot-docs**. Shell
auth/tenant/équipes → **pivot-core**.

---

## Équipe experte

Toute contribution mobilise les experts concernés — les mentionner explicitement dans la réponse.

| Expert | Domaine |
|--------|---------|
| **Architecte Java / Spring** | Architecture Spring Boot, patterns (Repository, Service, DTO), SOLID |
| **Architecte BDD PostgreSQL** | Schéma `collaboratif`, migrations Flyway, index, intégrité référentielle |
| **Architecte Temps Réel / WebSocket** | STOMP, isolation par room/board (`EN08.1`), relai ActiveMQ, présence, scalabilité multi-instance |
| **Expert DevSecOps** | CI/CD GitHub Actions, SonarCloud, Semgrep, Gitleaks, Plumber, SBOM, Semantic Release |
| **Expert Red Team** | Injection via messages WebSocket, IDOR cross-board, cross-tenant, CSRF |
| **Expert Blue Team** | Spring Security hardening (une fois branché), CORS, audit log, réponse aux rapports Red Team |
| **Expert OIDC / IAM** | Consommation du contrat `pivot-core-starter` une fois publié — validation opaque tokens, `TenantContext` |
| **Expert QA** | Stratégie TU/TI, Testcontainers, coverage ≥ 85 %, non-régression temps réel (latence, perte de paquets) |
| **Expert RGPD** | Contenu utilisateur sur les boards/formulaires, droits des personnes, export/suppression |
| **Product Owner** | Backlog markdown pivot-docs (EPIC E30), Epics, US, critères d'acceptation, priorisation |
| **Scrum Master** | Coordination, sprints, impediments, backlog consistency |
| **Architecte Modules** | Implémentation de `PivotModule` une fois `pivot-core-starter` consommable, isolation inter-modules |
| **Expert PR Review** | Relecture croisée neutre : architecture, lisibilité, dette technique, respect des standards PIVOT |
| **Experts Angular / UX/UI** | → **pivot-collaboratif-ui** |

### Faire appel aux experts

| Type de tâche | Expert(s) |
|---------------|-----------|
| Controller, Service, Repository Java | **Architecte Java / Spring** |
| Schéma BDD, migration Flyway, requête @Query | **Architecte BDD PostgreSQL** |
| WebSocket, STOMP, isolation room, présence temps réel | **Architecte Temps Réel / WebSocket** |
| Tests TU/TI, Testcontainers, couverture | **Expert QA** |
| CI/CD, GitHub Actions, Plumber, SBOM | **Expert DevSecOps** |
| Vulnérabilité sécurité, vecteur d'attaque | **Expert Red Team** → **Expert Blue Team** |
| Consommation contrat pivot-core-starter, rôles | **Expert OIDC / IAM** + **Expert Blue Team** |
| RGPD, contenu utilisateur, droits des personnes | **Expert RGPD** |
| Backlog, US, acceptance criteria | **Product Owner** |
| Enregistrement module PIVOT, activation | **Architecte Modules** |
| Review finale PR (après "prêt pour review") | **Expert PR Review** |
| Bug inexpliqué | **Architecte Java** en premier, puis **Expert Red Team** si suspicion sécurité |
| Frontend Angular, SCSS, composants | → **pivot-collaboratif-ui** |

**Règles :**
- Mentionner l'expert explicitement quand son domaine est engagé.
- Toute faille Red Team = correction Blue Team **avant** tout merge.
- Changement du contrat de module ou de la dépendance `pivot-core-starter` = coordination
  **avec pivot-core obligatoire**.

---

## Backlog — Fichiers markdown

> **Sources de vérité :**
> - Hiérarchie backlog + conventions : `pivot-docs/docs/backlog/README.md`
> - Sprints, assignation US, état avancement : **`pivot-docs/docs/backlog/sprints/`**
> - Backlog opérationnel : `pivot-docs/docs/backlog/EPIC-collaboration/` — EPIC **E30**, noyau
>   whiteboard **F08.x/EN08.x** (`Phase: Socle`, non verrouillé) + reste du périmètre benchmark
>   (`Phase: phase-3`, verrouillé jusqu'à déclaration "Socle terminé")

### Hiérarchie
`EPIC → FEATURE (valeur) / ENABLER (technique) → US` · clé `E30 → F30.x / EN30.x → US30.x.y`
(noyau whiteboard : `F08.x / EN08.x → US08.x.y`).

### Champs du Project

| Champ | Valeurs |
|-------|---------|
| Item Type | Epic / Feature / Enabler / US |
| Parent | clé du parent (ex. `E30`, `F30.1`) |
| Stage | ⬜ (pas encore terminé) / ✅ (Done — recette mainteneur). États intermédiaires internes, non persistés → pivot-docs/docs/backlog/README.md §2/§5 |
| Priority | Critical / High / Medium / Low |
| Module | `collaboratif` |
| Phase | Socle / v1-enterprise / phase-3 |
| Sprint | Sprint 1…N |
| Size | XS / S / M / L / XL |

### Template US, Definition of Ready, vagues → `pivot-docs/docs/backlog/README.md`.

---

## Breaking Points

### Step 0 — Challenge PO avant implémentation

Avant tout code, le **PO Agent** challenge les ACs de l'US :

1. Vérifier DoR — story complète, ACs Given/When/Then, AC erreur + sécurité
2. Calculer Gate 1 : **= 100** → procéder · **< 100** → PO Agent réécrit ACs → recalculer
3. AC ambigus à l'implémentation → PO Agent clarifie, jamais d'interprétation unilatérale
4. **AC supposant `pivot-core-starter` disponible alors qu'il ne l'est pas** → bloquant, signaler
   au mainteneur avant tout Gate 1 (voir section dédiée ci-dessus)

Pas de blocage humain — Claude autonome de A à Z sur la validation des ACs (hors point 4).

### Breaking Point 2 : Gate 4 MERGE < 60 ou hard block

Tout PR avec :
- Label `security` ou `breaking-change`
- Gitleaks secret détecté
- Modification du contrat de module sans coordination pivot-core
- Ajout d'une dépendance `fr.pivot:pivot-core-starter` avant publication réelle confirmée

→ Label `needs-human-review` + score breakdown + attendre le mainteneur.

---

## Workflow — Organisation par sprint

Travail organisé par sprint. Référence : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint).

**Principes :**
- **Une branche par US / Enabler** — `feat/{us-id}-{slug}` (ex. `feat/us08-1-1-creer-tableau`)
- **Agents en parallèle** — un agent par item du sprint, branches séparées
- **Backlog pivot-docs** — mises à jour `sprints/sprint-{N}.md` (état interne courant) ; le `Stage` du frontmatter US n'est modifié qu'à la création (`⬜`) et au passage `✅` (recette mainteneur), committés sur la branche de l'US
- **Issue GitHub liée** — avant de démarrer un item, vérifier qu'une issue existe dans **ce repo** pour cet US/Enabler (recherche par id/titre). Absente → la créer (titre `{id} — {titre US}`, corps = lien vers le fichier backlog pivot-docs + AC). **Déjà assignée** (humain ou agent en cours) → item déjà pris, ne pas démarrer, passer au suivant. Sinon → se l'auto-assigner immédiatement (`gh issue edit {N} --add-assignee @me`) avant le premier commit — verrouille l'item, empêche qu'un autre agent ou une autre personne ne le reprenne en parallèle. Référencer l'issue dans la PR (`Closes #N`) — fermeture automatique à la fusion, jamais de fermeture manuelle en double.

## Workflow — Merge séquentiel autonome (plusieurs PR)

Quand plusieurs PR sont ouvertes/en attente sur ce repo (ex. plusieurs items d'un même sprint),
Claude détermine seul l'ordre de fusion et l'exécute de bout en bout, sans confirmation par PR :

1. **Ordre** — dépendances fonctionnelles entre items d'abord, puis fichiers partagés
   (migrations Flyway, config Spring commune) pour minimiser les rebases en cascade.
2. **Par PR, dans cet ordre :**
   - Rebase sur `main` à jour (jamais de merge commit)
   - Conflit → résolution manuelle réelle (jamais `--theirs`/`--ours` aveugle) : lire les deux
     côtés, comprendre l'intention de chacun, fusionner le contenu
   - Rebase sans conflit mais fichier partagé → vérifier quand même qu'aucune régression
     sémantique silencieuse ne s'est introduite (ex. une clé de config écrasée par l'auto-merge git)
   - `mvn verify -q` local avant push (ou vérification équivalente si Docker indisponible en
     sandbox — s'appuyer sur la CI réelle pour la partie Testcontainers)
   - Push, attendre la CI réelle en boucle synchrone (jamais d'attente passive d'une notification)
   - Gate 4 selon les seuils déjà définis ci-dessous → squash-merge dès convergence
3. **Dernier item du sprint courant** (vérifier `pivot-docs/docs/backlog/sprints/sprint-{N}.md`)
   → le commit de squash-merge porte le marqueur de release (voir *Workflow — Release*
   ci-dessous), tous les autres non.
4. Incident CI rencontré en cours de route → diagnostiquer et corriger avant de continuer la
   séquence, pas de contournement silencieux.

## Workflow — Release

Le déclenchement d'une release (`release.yml` : version, publish Maven/Docker, tag, changelog)
n'a lieu **qu'en fin de sprint**, jamais à chaque merge — un merge ordinaire ne doit ni bumper de
version ni publier quoi que ce soit.

- **Déclencheur** : le commit du squash-merge du **dernier item d'un sprint** porte le trailer
  `Release-Trigger: true` **sur sa propre ligne, seul, rien d'autre** (`grep -qxE` — match exact
  de ligne entière, jamais une simple sous-chaîne — cf. incident réel documenté sur
  `pivot-core/CLAUDE.md` et `pivot-ui/CLAUDE.md`, section Workflow — Release).
- **Pourquoi** : sans cette règle, chaque merge déclenche `release.yml` — plusieurs merges
  rapprochés calculeraient tous la même "prochaine version" (aucun tag encore créé entre eux) et
  le second à publier échouerait en conflit sur GitHub Packages.
- **Effet** : la release qui finit par se déclencher regroupe automatiquement, dans une seule
  entrée de changelog, tous les commits accumulés depuis le dernier tag — comportement natif de
  semantic-release, pas une fonctionnalité à coder.
- **Ajout du trailer** : `gh pr merge --squash --body "...

Release-Trigger: true"` — trailer sur sa propre ligne finale, précédée d'une ligne vide, jamais
  intégré dans une phrase. Uniquement sur le merge identifié comme dernier item du sprint courant.
- **Exception hors cycle (2026-07-09)** : première release jamais déclenchée sur ce repo,
  immédiatement après le merge d'EN08.3 (`#46`, correctif de la faille d'authentification
  cross-tenant IDOR). Aucune image `pivot-collaboratif-core` publiée à ce jour n'est saine —
  déclenchement hors fin de sprint pour publier la première image corrigée, confirmation
  explicite du mainteneur.

## Workflow — Autoloop PR

Après toute modification sur une branche de travail — US/Enabler (`feat/{us-id}-{slug}`) ou
hors sprint (`fix/`, `refactor/`, `chore/`, `docs/`) — **sans exception** :

1. Ouvrir une PR (draft) vers `main`
2. **Autoloop** (20 itérations max) :
   - **En parallèle :**
     - **Review neutre** — Expert PR Review : architecture, AC, sécurité, dette
     - **CI** — `mvn verify -q` = 0 erreur/warning · Gitleaks clean · Gate 3 hard blocks
   - **Corrections** — tous les findings résolus, commit `fix({scope}): ...`
   - **Convergence** — Gate 4 ≥ 85 ET CI verte → sortir
3. Gate 4 ≥ 85 :
   - Sortir la PR du mode draft (`gh pr ready`)
   - État interne Review consigné dans `sprints/sprint-{N}.md` (branche/PR dédiée `pivot-docs` — jamais de commit cross-repo) ; `Stage` frontmatter US reste `⬜`
   - **Gate 5** — générer/mettre à jour la spec fonctionnelle et technique figée `pivot-docs/docs/specs/E30/{us-id}-{slug}.md`
   - Signal mainteneur
4. Blocage 20 boucles → Breaking Point 2

## Workflow — Ordre d'exécution par US (dans un sprint)

| Étape | Contenu |
|-------|---------|
| **1. Code** | Java + JavaDoc |
| **2. Tests** | JUnit 5 TU + Testcontainers TI — **dans le même commit** |
| **3. Qualité** | Checkstyle · SpotBugs verts |
| **4. Gate 2** | Coverage check : ≥ 85 % → continuer · 70–84 % → compléter · < 70 % → stop |
| **5. Backlog** | Mise à jour `sprints/sprint-{N}.md` + statut US **obligatoire avant commit** |
| **6. E2E** | — (délégué à pivot-collaboratif-ui) |
| **7. Commit** | `git add` fichier par fichier · commits atomiques sur branche `feat/{us-id}-{slug}` |

> **E2E délégué à pivot-collaboratif-ui.** Étapes 5 et 7 non différables.

### Approche tests

Écrire le code d'abord, puis les tests couvrant toutes les branches et conditions limites. TDD strict non utilisé.

**Exception :** quand le contrat d'une API ou d'un canal WebSocket est flou — écrire les tests en premier pour forcer la clarification.

---

## Workflow — Vérifications avant push autonome

**Condition absolue avant tout push autonome : 0 erreur, 0 warning.**

Claude exécute ces commandes **sans attendre d'instruction** :

```bash
mvn verify -q        # compile + tests + Checkstyle + SpotBugs
```

Rapporter ✅ ou stderr complet. Toute erreur ou warning non justifié = **stop, corriger avant push**.

---

## Workflow — Branches

| Préfixe | Usage | Exemple |
|---------|-------|---------|
| `feat/{us-id}-{slug}` | Implémentation d'une US | `feat/us08-1-1-creer-tableau` |
| `feat/{en-id}-{slug}` | Implémentation d'un Enabler | `feat/en08-1-ws-room-isolation` |
| `fix/{id}-{slug}` | Correction bug hors sprint | `fix/12-flyway-schema-collision` |
| `refactor/{id}-{slug}` | Refactoring hors sprint | `refactor/18-service-layer` |
| `chore/{slug}` | CI, deps, config | `chore/plumber-config` |
| `docs/{slug}` | Documentation hors sprint | `docs/adr-stomp-broker-relay` |

**Règles :**
- Jamais de travail direct sur `main`
- **Une branche = un item de sprint** (US ou Enabler)
- **Backlog pivot-docs committé sur la branche de l'US**
- Rebase avant merge → squash WIP
- `git push --force-with-lease` uniquement sur branches de travail

**Création de branche US — procédure obligatoire :**
```bash
git checkout main
git pull origin main
git checkout -b feat/{us-id}-{slug}
```
Branche existante → `git checkout feat/{us-id}-{slug}` directement.

---

## Workflow — Commits

Format **Conventional Commits** (`type(scope): message`) — alimente Semantic Release pour le versioning automatique.

| Commit | Contenu typique |
|--------|----------------|
| `feat(db):` | nouvelle migration Flyway (table, colonne, contrainte) → minor bump |
| `fix(db):` | correction migration Flyway existante → patch bump |
| `feat(backend):` | service, repository, controller |
| `fix(backend):` | correction bug backend |
| `feat(api):` | endpoint REST, DTO |
| `fix(api):` | correction endpoint ou contrat API |
| `feat(whiteboard):` | canvas, tableaux, partage/rôles (F08.x) |
| `feat(quiz):` | quiz interactif, sondages |
| `feat(session):` | session live, facilitation d'atelier |
| `feat(forms):` | moteur de formulaire |
| `feat(ws):` | WebSocket, STOMP handlers, isolation room |
| `fix(ws):` | correction bug WebSocket / STOMP |
| `test:` | ajout ou correction de tests (TU, TI) sans changement de code prod |
| `ci:` | GitHub Actions workflows, Plumber |
| `docs:` | README, CLAUDE.md, ADR |
| `security:` | correctif sécurité — **hard block Gate 4, review humaine** |

Co-author sur chaque commit : `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`

---

## Gates ACDD — Confidence Gates

Score 0–100, jamais booléen. Scores/décisions consignés en **commentaire de PR**. Le statut vit
dans le champ **Stage** du frontmatter US (pivot-docs).

| Gate | Moment | Seuils |
|------|--------|--------|
| **1 — READINESS** | Avant implémentation | PO Agent self-challenge · = 100 → état interne Ready → procéder (Stage frontmatter reste ⬜) · < 100 → PO Agent réécrit ACs |
| **2 — COVERAGE** | Par commit | ≥ 85 → continuer · 70–84 → compléter tests · < 70 → stop |
| **3 — QUALITY** | Après CI verte | Hard blocks : secret Gitleaks, label `security`/`breaking-change`, modif contrat module/dépendance pivot-core |
| **4 — MERGE CONFIDENCE** | Avant merge | ≥ 85 → merge autonome · 60–84 → merge documenté · < 60 → Breaking Point 2 |

**Checks Gate 1 :** AC testables (40) · dépendances résolues (20) · impact contrat module (15) · AC sécurité ≥ 1 (15) · pas de cycle (10)

**Checks Gate 2 :** AC couverts (50) · pas de code non testé (30) · tests non triviaux (20)

**Checks Gate 3 :** SonarCloud ≥ 80 % (25) · zéro finding critique/high (25) · linters clean (20) · Gitleaks clean (20) · build Docker (10)

**Format du commentaire de PR (gate)** : `gate` (READINESS | COVERAGE | QUALITY | MERGE_CONFIDENCE), `score`, `decision`, `breakdown`, `notes`.

---

## Agents IA — Rôles et cycle ACDD

### Philosophie

**ACDD (Acceptance Criteria Driven Development)** — gates de confiance continues.

- Gates → score (0–100), jamais booléen pass/fail
- Chaque gate → consigné en **commentaire de PR** (pas de fichier committé)
- Breaking Points = seuls moments d'intervention humaine obligatoire

### Rôles

| Agent | Responsabilité |
|-------|---------------|
| **PO Agent** | Génère Epics et US, rédige AC, clarifie AC ambigus |
| **Architect Agent** | Valide AC techniques, identifie impact contrat de module et dépendance pivot-core-starter |
| **Security Agent** | Challenge AC (Red Team), valide fixes (Blue Team) |
| **Dev Agent** | Implémente sur branche dédiée, s'auto-évalue via gates |
| **QA Agent** | Rédige specs E2E (déléguées à pivot-collaboratif-ui), valide couverture |
| **PR Review Agent** | Exécute Gate 3 + Gate 4, merge ou escalade selon score |

### Format des AC

```markdown
- [ ] Given [contexte], when [action], then [résultat observable]
- [ ] Error case: given [input invalide], system retourne [erreur / status code]
- [ ] Security: [propriété de sécurité qui doit tenir]
```

Chaque AC mappe à au moins un test. AC sans test = non implémenté, peu importe le code présent.
AC ambigu à l'implémentation → **stopper et demander au PO Agent** — jamais d'interprétation unilatérale.

### Labels PR

| Label | Signification |
|-------|--------------|
| `feat` | Nouvelle fonctionnalité |
| `fix` | Correction de bug |
| `security` | Impact sécurité — hard block Gate 4, review humaine |
| `breaking-change` | Changement de contrat — hard block Gate 4, review humaine |
| `module-contract` | Changement contrat de module — hard block Gate 4 |
| `needs-human-review` | Gate 4 < 60 ou hard block — décision humaine requise |
| `auto-approved` | Gate 4 ≥ 85 — mergé automatiquement |
| `chore` | Maintenance, CI, dépendances |
| `docs` | Documentation uniquement |

### Post-merge

```bash
# 1. Mainteneur : passe Stage: ⬜ → ✅ dans le frontmatter US (recette humaine — jamais Claude)
# 2. Débloquer les US dépendantes
# 3. Nettoyer la branche
git push origin --delete feat/{us-id}-{slug}
```

---

## Standards de code

### Java (backend)

- JavaDoc sur toutes les classes et méthodes publiques
- Checkstyle (config projet, `checkstyle.xml`)
- SpotBugs — zéro warning ignoré · aucune suppression inline (`@SuppressFBWarnings`) sans validation explicite du mainteneur
- Pas de logique dans les contrôleurs — déléguer aux services
- DTOs pour toutes les entrées/sorties API — **jamais les entités JPA directement**
- Pas de `@Transactional` sur les contrôleurs — uniquement sur les services

### Général

- Pas de secrets dans le code — variables d'environnement
- Toute action state-changing → log structuré JSON (backend)
- **`// NOSONAR` : zéro, jamais.** Tout faux positif Sonar se marque côté SonarCloud (UI "Won't fix" / "False positive", ou exclusion centralisée) — aucune exception.
- **`// nosemgrep` : interdit par défaut**, autorisé **uniquement avec la validation explicite du mainteneur**.

---

## Système de modules

Ce repo implémentera `PivotModule` (contrat exposé par `pivot-core-starter`) une fois ce dernier
réellement publié et consommable (voir section "Dépendance pivot-core-starter" ci-dessus) :

```java
public interface PivotModule {
    String getId();        // "whiteboard", "quiz", "session", "forms"…
    String getName();      // nom affiché en UI
    String getVersion();
    boolean isEnabled(TenantContext ctx);  // activable par admin tenant
}
```

- Module désactivé = 403 côté API + module non chargé côté Angular (une fois le contrat branché)
- Aucune logique inter-module directe — bus d'événements typés
- Changement de contrat de module = **hard block Gate 4 + Breaking Point 2**

---

## Authentification

**Branchée (EN08.3).** `fr.pivot.collaboratif.context.RequestPrincipalResolver` extrait le
bearer token du header `Authorization` (préfixe `Bearer ` insensible à la casse) et délègue à
`fr.pivot.core.auth.AuthenticatedPrincipalResolver` (contrat `pivot-core-starter`), implémenté
par `fr.pivot.collaboratif.auth.TokenValidationService` — validation dupliquée localement
(SHA-256, expiration, révocation, désactivation tenant/utilisateur) directement contre
`public.access_tokens`/`public.users`/`public.tenants`, jamais un appel réseau vers `pivot-core`
(ADR-022). Remplace l'ancien mécanisme de stub à en-têtes `X-Pivot-User-Id`/`X-Pivot-Tenant-Id`
non vérifiés. Toute requête sans token valide reçoit un 401 générique, sans fuite de la cause
exacte (token inconnu/expiré/révoqué/tenant désactivé/utilisateur désactivé — indistinguable côté
client). **Toujours étendre ce mécanisme, jamais de réimplémentation locale parallèle** (JWT,
session maison) — l'algorithme de validation doit rester la duplication fidèle de celui de
`pivot-core` (`fr.pivot.auth.service.TokenService#validate`).

---

## Audits

Dans **pivot-docs** — un fichier par catégorie, mis à jour en place. **Jamais de fichiers datés.**

---

## Règles absolues

| Interdit | Raison |
|----------|--------|
| `--no-verify` | Contourne les hooks qualité |
| `git push origin main` (push direct) | Jamais — tout code passe par PR + review (sauf commit initial de bootstrap) |
| `git push --force` sur `main` | Jamais — le mainteneur uniquement si nécessaire |
| `git add .` en bloc | Risque d'inclure `.env`, clés, binaires |
| Merger avec label `security` sans revue humaine | Hard block Gate 4 |
| Commiter `.env`, tokens, secrets, certificats | Exposition définitive |
| Entités JPA exposées directement en API | Fuite de schéma, IDOR |
| Logique métier dans les contrôleurs | Viole la séparation des couches |
| Module désactivé avec routes accessibles | Contournement restriction admin (une fois le contrat branché) |
| Implémenter sans US tracée dans les fichiers markdown backlog | Perte de traçabilité |
| Dépendance `fr.pivot:pivot-core-starter` avec version devinée | Coordonnée Maven fictive — vérifier l'état de publication avant toute tentative |
| Réimplémentation locale d'un mécanisme d'auth (JWT, session maison) | Doit venir exclusivement de `pivot-core-starter` — dérive d'architecture |
| `tenantId` extrait du body / header dans un endpoint | IDOR cross-tenant — extrait exclusivement du `RequestPrincipal` résolu du token porteur |

---

## Règle transversale sécurité — Isolation tenant

**Tout endpoint `/api/collaboratif/*` :**
- Extrait le `tenantId` **exclusivement** du `RequestPrincipal` résolu depuis le token porteur (EN08.3)
- N'accepte **jamais** un `tenantId` ou `userId` venant du body JSON, d'un query param ou d'un header custom
- Si `{boardId}`, `{sessionId}` ou `{formId}` dans le path → vérifier l'appartenance au tenant courant **avant** tout traitement
- Appartenance invalide → **404** (pas 403 — ne pas confirmer l'existence de la ressource cross-tenant)
- Isolation WebSocket par room (`EN08.1`) : un client ne reçoit jamais les messages d'un board/session d'un autre tenant, même en cas de collision d'id

---

## Boucles de problèmes — règle d'escalade

### Limite 10 commandes en échec successif

Si **10 commandes consécutives échouent** (toute combinaison : build, test, lint, push, CI) sur une tâche :
1. **Stopper la tâche courante** — ne pas impacter les agents parallèles sur d'autres US
2. **Poster un commentaire de gate** avec `decision: ESCALATED`, liste des 10 échecs, contexte
3. **Label `needs-human-review`** + signal mainteneur
4. **Proposer une alternative** (approche différente, découpage)

Le compteur se remet à zéro dès qu'une commande réussit.

### Limite 20 push — autoloop PR Review

Voir section **Workflow — Autoloop PR** — au-delà de 20 push correctifs → Breaking Point 2 automatique.

### Règle 2 tentatives (stratégie identique)

Après **2 tentatives** (même stratégie ou variantes proches) :
1. **Stopper** — ne pas continuer à boucler
2. **Poster un commentaire de gate sur la PR** avec `decision: ESCALATED`, contexte complet, tentatives effectuées — **jamais committer un fichier de gate**
3. **Signaler** au mainteneur : blocage, tentatives, raison de l'échec — label `needs-human-review`
4. **Proposer** une alternative : approche différente, outil différent, contournement

Ne jamais enchaîner plus de 2 tentatives sans informer le mainteneur.

---

## Skills — Knowledge Cards

Index : `.project/skills/_index.yaml`

| Skill | Fichier | Charger quand |
|-------|---------|---------------|
| `skill-spring-architecture` | `skill-spring-architecture.yaml` | Tout fichier Java (Controller, Service, Repository, DTO) |
| `skill-bdd-flyway` | `skill-bdd-flyway.yaml` | Migration Flyway, entité JPA, requête @Query |
| `skill-oidc-security` | `skill-oidc-security.yaml` | Une fois `pivot-core-starter` branché — fichier auth/, SecurityConfig, AC sécurité |
| `skill-module-system` | `skill-module-system.yaml` | Fichier modules/ ou registry/, US module |
| `skill-ac-traceability` | `skill-ac-traceability.yaml` | **Toujours** — toute implémentation d'US, Gate 2, Gate 4 |
| `skill-testing-strategy` | `skill-testing-strategy.yaml` | Nouveau test, coverage < 85 % (seuil Gate 2), Testcontainers |
| `skill-devops-cicd` | `skill-devops-cicd.yaml` | Fichier .github/workflows/, Dockerfile, config CI |
| `skill-observability` | `skill-observability.yaml` | Nouveau log, nouvelle métrique, endpoint health |
| `skill-rgpd` | `skill-rgpd.yaml` | US touchant contenu utilisateur (board, formulaire, session) |
| `skill-i18n` | `skill-i18n.yaml` | MessageSource, emails multilingues, locale resolution, fallback |
| `skill-security-redteam` | `skill-security-redteam.yaml` | US touchant WebSocket/auth/modules, nouvel endpoint REST, AC sécurité |
| `skill-security-blueteam` | `skill-security-blueteam.yaml` | Rapport Red Team reçu, SecurityConfig, mécanisme auth modifié |
| `skill-pr-reviewer` | `skill-pr-reviewer.yaml` | Gate 3 (qualité CI), Gate 4 (décision merge), review PR |

**Règle :** avant d'écrire du code, identifier les skills applicables via l'index et les lire.
La skill `skill-ac-traceability` est toujours chargée pour toute US. Ces skills sont des cartes
méthodologiques génériques héritées de `pivot-core` (aucune ne référence de package spécifique
à `pivot-core` — vérifié au bootstrap) ; à spécialiser pour ce domaine (temps réel, whiteboard)
au fil des premières US si nécessaire.

---

## Parallélisation

Lancer un maximum d'actions en parallèle dans chaque message :

| Actions parallélisables | Exemples |
|------------------------|---------|
| Lectures indépendantes | Plusieurs `Read` / `Grep` / `Glob` |
| Linters | Checkstyle + SpotBugs lancés simultanément |
| Créations de fichiers indépendants | TU + TI d'une même feature |
| Recherches codebase | Plusieurs `Grep` sur cibles différentes |

Ne séquencer que ce qui dépend du résultat d'une étape précédente.
