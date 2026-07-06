# TODO — Setup manuel restant (pivot-collaboratif-core)

Ce repo a été bootstrappé avec :
- Branch protection classique sur `main` (1 review requise, 4 status checks **self-contained**
  requis — voir liste ci-dessous)
- Ruleset `protect-main` (deletion / non-fast-forward / historique linéaire) — identique à
  `pivot-core`

**Volontairement PAS activé** : le ruleset complet (10+ checks incluant SonarCloud, style
`main-protection` sur `pivot-core`) — il rendrait `main` définitivement non mergeable tant que
les items ci-dessous ne sont pas faits (pas de projet SonarCloud, pas de secrets configurés pour
ce repo précis).

## À faire avant d'activer le ruleset complet (tous les checks)

- [ ] **Créer le projet SonarCloud** pour `PIVOT-PLATFORM_pivot-collaboratif-core` (organisation
      `pivot-platform` sur sonarcloud.io) — `sonar-project.properties` déjà présent avec cette clé.
- [ ] **Ajouter le secret `SONAR_TOKEN`** (Settings → Secrets and variables → Actions) pour ce
      repo — token du projet SonarCloud créé ci-dessus.
- [ ] **Vérifier les secrets partagés au niveau organisation** (`GITLEAKS_LICENCE_KEY`,
      `SEMGREP_APP_TOKEN`, `PLUMBER_METADATA_TOKEN`, `PLUMBER_TOKEN`, `SEMANTIC_RELEASE_TOKEN`) —
      le token utilisé pour ce bootstrap n'a pas les droits `admin:org` pour lister les secrets
      organisation, donc **impossible de confirmer depuis cette session** si ce nouveau repo est
      bien inclus dans leur périmètre de visibilité (`All repositories` vs `Selected
      repositories`). Vérifier manuellement : Organization Settings → Secrets and variables →
      Actions → chaque secret → périmètre de visibilité doit inclure `pivot-collaboratif-core`.
      - Constat fait pendant le bootstrap : `pivot-core` lui-même a **0 secret au niveau repo**
        (`gh api repos/PIVOT-PLATFORM/pivot-core/actions/secrets` → `total_count: 0`) — donc ces
        6 secrets sont forcément des secrets **organisation**, jamais configurés par repo.
      - Si un de ces secrets n'est PAS scopé "All repositories" : l'ajouter au périmètre pour
        `pivot-collaboratif-core`, ou dupliquer en secret **repo-level** ici si le secret
        organisation doit rester restreint à `pivot-core` pour une raison de coût/licence
        (ex. `GITLEAKS_LICENCE_KEY` est souvent un nombre de sièges limité).
- [ ] **Une fois SONAR_TOKEN actif et les 5 autres secrets confirmés accessibles**, relancer une
      PR de test et vérifier que tous les jobs suivants passent en vert :
      `SonarCloud Analysis`, `SCA - Dependency Audit`, `Mutation Testing (PITest)`,
      `Gitleaks - Secret Scan`, `CodeQL - SAST`, `Semgrep - SAST`, `Plumber - CI/CD Compliance`.
- [ ] **Étendre le ruleset `main-protection`** (comprehensive, calqué sur `pivot-core`) une fois
      tout ce qui précède est vert :
      ```bash
      gh api repos/PIVOT-PLATFORM/pivot-core/rulesets/17948736 > /tmp/main-protection-ref.json
      # Adapter et créer via:
      # gh api repos/PIVOT-PLATFORM/pivot-collaboratif-core/rulesets -X POST --input <fichier-adapte.json>
      ```
      Contexts à inclure (ordre pivot-core) : `Code Quality - Java`, `Tests Backend (TU + TI)`,
      `SCA - Dependency Audit`, `Mutation Testing (PITest)`, `SonarCloud Analysis`,
      `SonarCloud Code Analysis`, `Gitleaks - Secret Scan`, `CodeQL - SAST`, `Semgrep - SAST`,
      `Plumber - CI/CD Compliance`.
- [ ] Optionnel : une fois ce ruleset complet actif, envisager de **retirer** les 4 contexts de
      la branch protection classique en double emploi (le ruleset comprehensive les couvre déjà)
      — ou les laisser en double (comportement actuel de `pivot-core`, qui garde les deux).

## Checks déjà requis (fonctionnent sans configuration supplémentaire)

Ces 4 checks n'utilisent que `secrets.GITHUB_TOKEN` (fourni automatiquement par GitHub Actions,
aucune configuration requise) — vérifié : `default_workflow_permissions: read` sur ce repo est
identique à `pivot-core` et n'empêche pas les permissions explicites déclarées par job
(`packages: write` etc.) :

- `Code Quality - Java` (Checkstyle + SpotBugs)
- `Tests Backend (TU + TI)` (JUnit 5 + Testcontainers + Redis service, coverage JaCoCo ≥ 80 %)
- `Maven deploy preview (PR)` (déploie une version PR-scoped vers GitHub Packages)
- `Docker preview image (PR)` (build + scan Trivy + push GHCR)

## Autre dépendance externe au module (pas un TODO CI/CD, mais bloquant pour le dev réel)

`fr.pivot:pivot-core-starter` n'est pas encore publié par `pivot-core` (son `pom.xml` publie
`fr.pivot:pivot-core`, pas un artefact "starter" séparé). Le développement réel des US du domaine
Collaboratif (whiteboard, quiz, session live, formulaire) ne peut pas brancher la sécurité
(opaque tokens, `TenantContext`) tant que ce starter n'est pas publié comme artefact Maven
consommable indépendamment — voir `CLAUDE.md`, section "Dépendance pivot-core-starter — état
réel", et le pré-requis explicite noté dans `pivot-docs/docs/backlog/EPIC-collaboration/README.md`
(EPIC E30 : *"Pré-requis EN17 : pivot-core-starter + @pivot/ui-core publiés avant
implémentation"*).
