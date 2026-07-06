## US liée

Closes #

## Description

<!-- Résumé des changements -->

## Type de changement

- [ ] `feat` — nouvelle fonctionnalité
- [ ] `fix` — correction de bug
- [ ] `security` — **hard block : review humaine obligatoire**
- [ ] `breaking-change` — **hard block : review humaine obligatoire**
- [ ] `refactor` — refactoring sans changement de comportement
- [ ] `chore` — maintenance, CI, dépendances
- [ ] `docs` — documentation

## Couverture des AC

| Critère d'acceptation | Test(s) associé(s) |
|-----------------------|--------------------|
| Given … when … then … | `TestClass#testMethod` |
| Error case : … | `TestClass#testErrorMethod` |
| Security : … | `TestClass#testSecurityMethod` |

## Gate de confiance

<!-- Les scores de gates sont consignés en commentaire de PR — pas de fichiers committés -->

- Gate 1 READINESS : score ___/100
- Gate 2 COVERAGE (dernier commit) : score ___/100
- Gate 3 QUALITY (après CI) : score ___/100
- Gate 4 MERGE CONFIDENCE : score ___/100

## Checklist

- [ ] Tous les AC couverts par des tests
- [ ] Linter clean (Checkstyle + ESLint)
- [ ] Aucun secret dans le code
- [ ] JavaDoc sur classes et méthodes publiques
- [ ] Migration Flyway créée si changement BDD
- [ ] Spec Playwright ajoutée / mise à jour (happy path + 1 erreur critique)
- [ ] Project GitHub org : `Stage → Review`
