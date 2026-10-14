# Jade Genesis — Evidence Review V1 (12 octobre 2026)

## Périmètre
Copie isolée: `C:\Users\alexa\Documents\JadeGenesis-MemoryAudit-20261012`.
Source: `vadorus/Jade-genesis`, HEAD `f486452`. Aucune modification de production, aucun commit, push, merge ou déploiement.

## Implémentation
- `NightEvidenceReviewer.kt`: décisions déterministes `NEEDS_EVIDENCE`, `NEEDS_INDEPENDENT_SOURCES`, `NEEDS_EXPERIMENT`, `CONTESTED`, `READY_FOR_REVIEW`. Une contre-preuve inspectée bloque `READY_FOR_REVIEW`. Le moteur ne promeut jamais la connaissance lui-même.
- `NightEvidenceLedger.kt`: conservation locale bornée (96 références / 8 décisions), IDs/empreintes/horodatage, pas de contenu des documents ou conversations.
- `NightCycleEngine.kt`: examine l'agenda des connaissances incertaines avec ce ledger, enregistre les verdicts et le nombre de contradictions. Aucun accès Internet ajouté.
- `NightEvidenceReviewerTest.kt`: neuf tests de validation de politique.
- `MemoryPersistenceInstrumentation.kt`: phases séparées `evidence_seed`, `evidence_verify`, `evidence_cleanup` pour Android réel, uniquement sur le package de validation.

## Résultats constatés
- Compilation `testDebugUnitTest`, `assembleDebug` et `assembleDebugAndroidTest` : réussie via Gradle 9.6 (home).
- 229 tests unitaires Android, zéro échec.
- Pixel 10 : `evidence_seed` PASS (2 documents fictifs indépendants + 1 test fictif -> READY_FOR_REVIEW; 1 contre-preuve fictive supplémentaire -> CONTESTED).
- Pixel 10 : `evidence_verify` PASS après une nouvelle instrumentation : quatre références retrouvées, verdict `CONTESTED` conservé, mémoire toujours non vérifiée, aucune promotion.
- Pixel 10 : `evidence_cleanup` PASS : 1 souvenir, 4 références, 1 verdict fictifs supprimés.
- Application personnelle `com.jadegenesis.mobile` 0.1.22 inchangée; app `com.jadegenesis.mobile.validation` 0.1.22-validation.
- Diff Git sans erreur; toutes modifications locales non commitées.

## Limites importantes
- Les sources et résultats d'expériences du test sont fictifs : ce test démontre l'algorithme de traçabilité, de contradiction et sa persistance, PAS la véracité d'une vraie connaissance.
- Les statuts `inspected` et les empreintes sont des métadonnées fournies par un futur collecteur; ni l'authenticité de la source, ni l'exécution indépendante du test ne sont encore vérifiées automatiquement.
- Aucune contre-recherche publique automatisée, aucun apprentissage profond, aucune création de compétence automatique, aucune promotion de connaissance.
- En validation Android, les tâches automatiques et la permission INTERNET sont désactivées; le test isolé ne contacte pas la production.
- Les notions hors de la fenêtre de 36 h de l'agenda actuel et hors des souvenirs récents ne sont pas encore automatiquement réexaminées.

## Prochaine étape technique
Créer un collecteur autorisé de sources primaires (documents officiels et API), avec empreinte calculée sur les octets réellement récupérés, source URL et provenance, puis un évaluateur de contre-preuves. Relier chaque test à un rapport signé/horodaté provenant de l'environnement isolé; tester sur des sujets réels avant de proposer toute validation humaine. Maintenir les garde-fous de confidentialité et les permissions.
