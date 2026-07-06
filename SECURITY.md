# Security Policy

## Versions supportées

| Version | Support |
|---------|---------|
| dernière release | ✅ Support complet |
| dernière - 1 | ✅ Correctifs sécurité uniquement |
| antérieures | ❌ Aucun support |

---

## Signaler une vulnérabilité

**Ne pas ouvrir d'issue publique GitHub pour une vulnérabilité de sécurité.**

Une divulgation publique avant qu'un correctif soit disponible expose tous les utilisateurs PIVOT.

### Comment signaler

Utiliser **GitHub Private Vulnerability Reporting** :

```
https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/security/advisories/new
```

### Informations à inclure

- Composant affecté (endpoint, migration Flyway, workflow CI/CD)
- Version du service et plateforme (OS, version Java)
- Description de la vulnérabilité et impact potentiel
- Étapes de reproduction — aussi détaillées que possible
- Preuve de concept si disponible (optionnel mais apprécié)

---

## Délais de réponse

| Étape | Objectif |
|-------|----------|
| Accusé de réception | 48 heures |
| Évaluation initiale | 5 jours ouvrés |
| Correctif — Critique (CVSS ≥ 9.0) | 7 jours |
| Correctif — Élevé (CVSS 7.0–8.9) | 30 jours |
| Correctif — Moyen / Faible | Prochaine release planifiée |

---

## Politique de divulgation

Divulgation coordonnée :

1. Le rapporteur soumet via advisory privé
2. Les mainteneurs accusent réception et évaluent la sévérité
3. Le correctif est développé sur une branche privée
4. Le correctif est releasé et taggé
5. L'advisory de sécurité est publié avec assignation CVE (si applicable)
6. Le rapporteur est crédité dans l'advisory (sauf anonymat demandé)

Pas de programme bug bounty actuellement.

---

## Périmètre

### Dans le périmètre

- **Backend** — endpoints REST, migrations Flyway (schéma `collaboratif`), isolation tenant
- **WebSocket / STOMP** — injection via messages, isolation par room/board, autorisation insuffisante (à venir, EN08.1)
- **Docker** — mauvaises valeurs par défaut en production, exposition de ports
- **CI/CD** — workflows GitHub Actions, permissions excessives, secrets exposés

### Hors périmètre

- Vulnérabilités dans les dépendances tierces (signaler au projet upstream)
- Problèmes nécessitant un accès physique à la machine hôte
- Attaques d'ingénierie sociale
- Déni de service par épuisement des ressources (limitation connue du self-hosting)
- Problèmes dans l'environnement de dev local (`compose.yml` sans profil production)

---

## Principes de sécurité

- **Pas d'entités JPA exposées en API** — DTOs uniquement, enforced par code review
- **Isolation tenant** — `tenantId` résolu exclusivement depuis le token porteur (via
  `fr.pivot:pivot-core-starter` une fois publié — voir `CLAUDE.md`), jamais accepté en body/query/header
- **Secrets via variables d'environnement** — jamais en dur dans le code (enforced par Gitleaks CI)
- **SBOM généré à chaque release** — disponible dans GitHub Releases
- **SLSA L3** — provenance attestée sur chaque release (JAR + image Docker)
- **Pas de `--no-verify`** — hooks qualité non contournables

---

## Scanning actif en CI

| Outil | Couverture |
|-------|-----------|
| Gitleaks | Secrets dans le code et l'historique git |
| CodeQL | SAST Java |
| Semgrep | OWASP Top 10, injection SQL, Spring |
| Trivy | SCA — dépendances Maven + image Docker |
| Plumber | Conformité et hardening des workflows CI/CD |
| OpenSSF Scorecard | Score de sécurité open-source global |
