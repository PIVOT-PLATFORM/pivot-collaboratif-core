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

```bash
cp .env.example .env
docker compose up -d postgres redis
./mvnw spring-boot:run
```

- API : <http://localhost:8083/api/collaboratif>
- Healthcheck : <http://localhost:8083/api/collaboratif/actuator/health>

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
