# Contrôle de coordination et limites

Ce correctif complète la PR de coordination existante. Il reste local tant que le
push puis l'intégration n'ont pas été expressément autorisés.

## Ce que vérifie le contrôle

- Métadonnées actualisées via l'API ; auteur et relecteur déclarés distincts.
- Issue ouverte avec réservation cohérente et fichiers compris dans ses zones.
- Inventaire paginé de toutes les PR ouvertes, brouillons et bases différentes
  inclus ; mêmes tâches, mêmes fichiers et anciennes destinations de renommage.
- Aucun succès si une liste de fichiers est tronquée, une requête échoue ou la
  PR change pendant le contrôle. Les anciennes PR n'ont plus de dispense.
- L'exception `Tâche: COORDINATION` est réservée au numéro de PR initial et à
  une liste exacte de fichiers. Elle exige aussi auteur, relecteur et tests.

Le workflow `pull_request_target` exécute seulement sa version déjà intégrée
dans la branche par défaut (`main`), selon la documentation GitHub actuelle.
Pour Godot, la branche produit `v013/demo-octobre` reste distincte de `master` :
installer le seul workflow de confiance sur `master` exige une opération autorisée
séparément. Aucune copie du code de la PR n'est extraite ou exécutée.
Son seul droit d'écriture est `checks:write`, pour publier `multi-agent-gate`
sur le SHA de tête courant. Le job de calcul porte un autre nom afin d'éviter
un résultat ambigu sur le SHA de base. Une erreur produit un échec du contrôle.

## Réservation d'une issue

Ajouter une seule ligne pour chacun de ces champs au corps de l'issue :

```text
Responsable-IA: Codex
Relecteur-IA: Claude
Réservation-état: Réservée
Zones: scripts/Example.gd, tests/example_test.gd
```

`Zones` accepte des fichiers exacts ou un répertoire avec `/` final ; ni glob,
ni chemin absolu, ni `..`. Vérifier cette réservation juste avant chaque reprise.
Une réservation textuelle ne prouve pas une autorisation humaine et ne constitue
pas un verrou atomique. Ne créer/modifier aucune issue sans autorisation adaptée.

## Précontrôle local

```text
python scripts/ai_preflight.py --repo vadorus/Jade-genesis --base main
```

Ajouter `--task NUMERO --writer Codex --reviewer Claude --path CHEMIN` pour
contrôler une zone de travail. `--resume-pr NUMERO` évite de compter la PR reprise
comme un concurrent seulement si la copie correspond à sa tête exacte.
Toute erreur produit un code de sortie non nul. Un inventaire valide laisse
toujours `write_authorized: false` : une commande ne crée pas de permission.
Ne pas exécuter ce script dans un pilote qui interdit `gh` ou le réseau ; faire
le contrôle hors du pilote et conserver la preuve datée dans la passation.

## Activation distante, distincte de ce correctif

1. Faire relire puis publier ce correctif dans la PR existante, sans créer une
   seconde PR de coordination. Aucun push n'est implicite.
2. Valider les essais locaux. L'intégration initiale du workflow dans la branche
   par défaut requiert une décision humaine séparée ; ce workflow ne peut pas
   contrôler son propre premier déploiement avant qu'il y existe. Pour Godot,
   installer la copie du seul workflow dans `master`, sans modifier son défaut,
   puis adopter séparément les consignes de la PR #90 sur la branche produit.
3. Après intégration autorisée, déclencher un événement de PR contrôlé et vérifier
   un cas accepté puis un cas refusé sur GitHub. Vérifier `check_run.head_sha`,
   nom `multi-agent-gate` et app GitHub Actions avant de le rendre obligatoire.
4. Activer le ruleset proposé seulement après accord : PR obligatoire,
   conversations résolues, tête à jour, contrôle obligatoire de source GitHub
   Actions, aucun contournement configuré, interdiction de suppression/force-push.
5. Avec un seul compte collaborateur, conserver zéro approbation GitHub requise
   évite le blocage par auto-revue impossible. Cela ne force pas la validation
   humaine. Pour la forcer, ajouter un relecteur GitHub indépendant autorisé puis
   exiger une approbation et la révocation des approbations devenues anciennes.

## Limites persistantes

- `Auteur-IA`, `Relecteur-IA`, réservation et `Tests` sont des déclarations. Le
  contrôle ne prouve ni l'identité d'une IA, ni l'exécution de tests, ni une revue.
- L'inventaire n'est pas une exclusion mutuelle. Une modification d'une autre PR
  ou d'une issue après le contrôle peut rendre un succès ancien obsolète. Le
  workflow réagit aux événements de la PR courante, pas à toutes les réservations.
  Rafraîchir l'inventaire et les contrôles avant fusion humaine.
- Des identifiants administrateur communs permettent de modifier les protections
  ou de fabriquer un autre workflow/check. Une séparation effective des droits et
  un contrôleur externe approuvé sont nécessaires contre un contournement volontaire.
- Les fichiers AGENTS.md/CLAUDE.md/GEMINI.md et la règle Antigravity `always_on`
  guident les agents. Ils ne constituent pas une interdiction au niveau du système.
  Leur chargement réel doit être vérifié dans chaque client après intégration.
- Desktop Commander donne accès à un appareil appairé et en ligne. Il ne réserve
  pas les tâches, ne synchronise pas les conversations et ne lance pas les trois
  IA automatiquement. Sa disponibilité n'autorise aucune action sensible.
