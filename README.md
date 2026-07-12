# pivot-collaboratif-core

<div align="center">

[![CI](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/actions/workflows/ci.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

</div>

Backend Java/Spring Boot du domaine **Collaboratif** de la suite PIVOT — whiteboard, quiz,
session live, formulaire. Schéma PostgreSQL dédié : `collaboratif`.

> **Statut : bootstrap.** Ce repo contient le squelette (build, CI/CD, sécurité) — aucune
> feature métier n'est encore implémentée. Voir [`CLAUDE.md`](CLAUDE.md) pour l'état exact des
> dépendances plateforme (`fr.pivot:pivot-core-starter` publié et branché depuis EN08.3) et
> [`TODO-SETUP.md`](TODO-SETUP.md) pour ce qu'il reste à configurer manuellement.

Frontend associé : [`pivot-collaboratif-ui`](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-ui).
Backend shell / auth : [`pivot-core`](https://github.com/PIVOT-PLATFORM/pivot-core).
Backlog : [`pivot-docs`](https://github.com/PIVOT-PLATFORM/pivot-docs) — EPIC E30 (Collaboration).

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 25 · Spring Boot 4.x · Maven · `--release 24` |
| BDD | PostgreSQL 18 (schéma `collaboratif`) · Spring Data JPA · Flyway |
| Cache | Redis (instance partagée avec les autres modules) |
| Tests | JUnit 5 · Mockito · Testcontainers |
| Observabilité | Spring Actuator · Micrometer · Prometheus |
| CI/CD | GitHub Actions · SonarCloud · Semantic Release · Plumber |
| Déploiement | Docker |

## Démarrage local

> **Credentials GitHub Packages requis.** Ce module dépend du package privé
> `fr.pivot:pivot-core-starter` (GitHub Packages, Maven). Toute résolution Maven — sur l'hôte
> comme dans le build Docker — exige des credentials, lus par `.mvn/settings.xml` depuis
> `GITHUB_ACTOR` / `GITHUB_TOKEN` (un login GitHub + un PAT `read:packages`, ou `gh auth token`) :
> ```bash
> export GITHUB_ACTOR="$(gh api user -q .login)"
> export GITHUB_TOKEN="$(gh auth token)"
> ```

**Option A — backend sur l'hôte (infra en conteneurs) :**

```bash
cp .env.example .env
docker compose up -d postgres redis
./mvnw spring-boot:run          # nécessite GITHUB_ACTOR/GITHUB_TOKEN exportés (cf. ci-dessus)
```

**Option B — tout en conteneurs :** `compose.yml` build le backend depuis le Dockerfile (secrets
BuildKit `github_actor`/`github_token`, sourcés du shell) :

```bash
docker compose up -d --build     # GITHUB_ACTOR/GITHUB_TOKEN exportés dans le shell
```

- API : <http://localhost:8083/api/collaboratif>
- Healthcheck : <http://localhost:9083/actuator/health> — l'Actuator est sur un **port de
  management séparé** (`management.server.port=9083`, EN04.2), avec context racine : quand ce port
  diffère de `server.port`, Spring Boot n'applique **pas** `server.servlet.context-path` aux
  endpoints Actuator (donc `/actuator/health`, pas `/api/collaboratif/actuator/health`).

> Pour lancer ce module **intégré à toute la plateforme** (aux côtés de pivot-core, des autres
> modules et du frontend), voir plutôt `pivot-core/README.md` §Développement local.

## Pipeline CI/CD

```
push / PR
  ├── Code Quality - Java       Checkstyle · SpotBugs
  ├── Tests Backend (TU + TI)   JUnit 5 + Testcontainers, coverage ≥ 80 %
  ├── SCA - Dependency Audit    Trivy (pom.xml)
  ├── Sécurité                  Gitleaks · CodeQL · Semgrep · Plumber
  └── PR uniquement             Mutation Testing (PIT, indicatif) · Docker preview · Maven deploy preview

Sur main (release) :
  └── Semantic Release · Docker GHCR · SBOM CycloneDX · SLSA L3 (JAR + image)
```

Détail des checks requis vs différés → [`TODO-SETUP.md`](TODO-SETUP.md).

## Documentation

| Sujet | Emplacement |
|-------|-------------|
| Instructions Claude Code + agents IA | [`CLAUDE.md`](CLAUDE.md) |
| Contribuer | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Sécurité & divulgation | [`SECURITY.md`](SECURITY.md) |
| Setup manuel restant (branch protection stricte, secrets) | [`TODO-SETUP.md`](TODO-SETUP.md) |
| Backlog du domaine (EPIC E30) | `pivot-docs/docs/backlog/EPIC-collaboration/` |

## Licence

[GNU Affero General Public License v3.0](LICENSE) — les modifications déployées comme service réseau doivent être publiées.
