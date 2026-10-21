# ORCH-MVP — répartition multi-IA, prototype isolé

Ce premier module prépare une **proposition** de distribution du travail entre **Claude** (architecte et relecteur), **Codex** et **Antigravity/Gemini** (implémentation). Il ne lance pas les clients IA, ne crée/modifie ni issue ni PR, n'effectue aucun commit et ne consomme aucune API de modèle.

## Tester le code

Depuis la racine du dépôt :

```bash
python3 -m unittest discover -s orchestration -p 'test_*.py' -v
python3 orchestration/dispatcher.py --state orchestration/example_state.json
```

Le fichier exemple est volontairement **non autorisé**, avec quotas inconnus. Il doit retourner un blocage : ne jamais traiter les valeurs exemples comme les quotas ou l'accord réels.

## Lire les réservations GitHub (lecture seule)

```bash
python3 orchestration/dispatcher.py --state orchestration/example_state.json --github
```

`--github` récupère les issues ouvertes de confiance et les fichiers des PR ouvertes du ou des dépôts inscrits dans les tâches. Sur les dépôts privés et si nécessaire pour les limites d'API, utiliser un jeton à droits minimaux exposé via `GITHUB_TOKEN` **dans l'environnement**, jamais dans le JSON ni dans un commit.

La lecture GitHub est **bloquante** en cas de données mal formées de confiance, de réponse incomplète, d'échec réseau/API ou de PR trop volumineuse. Les issues publiques créées par des inconnus ne réservent pas les fichiers du planificateur ; les utilisateurs supplémentaires explicitement autorisés doivent figurer dans `trusted_issue_authors`. Le contrôle officiel du dépôt demeure celui de la branche principale, pas ce prototype.

## Protocole

- Une tâche contient un ID, un dépôt, des zones, son type, une priorité, un coût approximatif et `authorized`. Sans autorisation humaine explicite, elle n'est pas attribuée.
- Chaque agent fournit **manuellement** `status` et `quota_units`. Inconnu (`null`) signifie **non planifiable**, pas « quota illimité ». Les quotas sont des unités de planification, pas des mesures de fournisseurs.
- Les zones se comparent par composants, sans distinction de casse et après normalisation Unicode NFC. Une zone réservée par une autre issue ou PR bloque l'attribution.
- Une tâche de sécurité/architecture va à Claude, revue par Codex. Une tâche d'implémentation/test/documentation va à Codex ou Antigravity, revue par Claude. Un agent auteur ne se relit jamais lui-même.
- Les attributions d'une même simulation réservent immédiatement leurs zones et débitent le budget des agents ; les dépendances non terminées bloquent.
- La sortie est **toujours** un `proposal_only`, jamais un ordre exécuté ou une preuve d'approbation. Elle ne fournit ni verrou atomique, ni validation de tests, ni permission GitHub.

## Ce qui manque pour une orchestration autonome réelle

Cette PR **ne connecte pas** Claude Opus, Codex ou Antigravity à un ordonnanceur actif. Il reste à faire valider le contrôle de confiance des PR #88/#100/#8, fiabiliser le stockage central des réservations, définir des états persistants et des heartbeats, puis ajouter des adaptateurs autorisés pour chaque agent avec budget/quota effectivement observés, passations et reprise après panne. L'application GitHub dédiée et la gestion des droits restent des étapes indépendantes. Aucun lancement de Claude, aucun déploiement, aucune fusion automatique.

Ticket de préparation : #90. Auteur : Codex ; relecture indépendante prévue : Claude. Aucun fichier de produit, sauvegarde ou configuration machine n'est touché.

## Phase A — contrôle d'autorisation et passation GitHub (21 octobre 2026)

En mode `--github`, le champ JSON `authorized` est **ignoré**. Pour vérifier une autorisation, le programme exige une issue ouverte de l'auteur de confiance, des Zones compatibles avec la tâche, et un commentaire de **vadorus**, dont GitHub renvoie `author_association: OWNER` :

```text
/autoriser <12 premiers caractères hexadécimaux du SHA-256>
```

Le format intégral historique (`/autoriser`, puis `sha256:<64 caractères>` à la ligne suivante) reste accepté. **Les commentaires dont `performed_via_github_app` n'est pas `null` sont toujours refusés**, même avec `login: vadorus` et `author_association: OWNER`.

L'empreinte SHA-256 porte sur **le corps intégral de l'issue et les Zones normalisées**. La forme courte compare exactement les 12 premiers caractères (48 bits) : elle est pratique sur téléphone, mais moins résistante aux collisions que les 64 caractères. Une édition du corps change normalement l'empreinte et rend le commentaire caduc ; un simple `/autoriser` sans empreinte est refusé. Aucun nouveau workflow n'a été ajouté. Pour obtenir le commentaire à copier (lecture seule, en partant d'un clone temporaire de la branche GitHub) :

```sh
GITHUB_TOKEN=<jeton en variable d'environnement> python3 orchestration/dispatcher.py \
  --state orchestration/example_state.json --prepare-approval 90
```

Le texte généré est une **préparation**, pas une approbation. Seul le compte GitHub `vadorus` peut le publier sur l'issue #90. En cas de modification ultérieure, recalculer l'empreinte et reprendre la procédure. Ne pas inscrire le jeton dans un fichier ou un commentaire GitHub.

La détection de la propre PR repose sur le même dépôt GitHub, le champ `Tâche: #N` dans le corps, `Auteur-IA` égal à `Responsable-IA` sur l'issue, une branche préfixée `codex/`, `claude/` ou `antigravity/`, et un dépôt de tête identique. **Deux PR pour la même tâche bloquent avec `duplicate_pr_for_task`**. Chaque PR est interrogée individuellement pour obtenir son vrai `changed_files`. Une erreur d'API, un jeton absent ou des fichiers manquants bloque la planification.

Quand Claude n'a plus de quota déclaré, Gemini/Antigravity peut relire **uniquement** les tâches `documentation` ou `testing` dont **toutes** les zones sont strictement sous `docs/` (sauf les fichiers `AI_*.md`), ou sous `tests/` (sauf `tests/test_ai_preflight.py`). Les réservations globales `docs/` ou `tests/`, toutes les zones `orchestration/`, tout autre chemin, et toute tâche d'implémentation restent exclus. Il n'y a jamais d'auto-relecture : Codex est l'auteur lorsqu'Antigravity relit. Un agent hors quota reste inconnu jusqu'à une déclaration fraîche ; ce programme **n'interroge aucun quota fournisseur**.

Ce module reste un **simulateur de propositions** : aucune tâche n'est envoyée à Claude, Codex ou Antigravity, aucune issue/PR n'est modifiée automatiquement, aucune API payante n'est appelée, aucun état de travail durable n'est encore mis à jour par le répartiteur.

Tests ajoutés dans `orchestration/test_dispatcher.py` : cas d'autorisation, auto-PR/PR étrangère/PR double, liste de fichiers incomplète, jeton manquant, erreur d'API et relecteur de secours. Pour R1–R3, **12 tests ciblés exécutés avec succès le 21/10/2026 sur une copie des fonctions modifiées**, sans accès GitHub ni appel d'IA ; la **suite complète du dépôt n'a pas été relancée** dans cet environnement. Elle reste obligatoire pour la relecture Claude. Aucun nouveau workflow GitHub Actions n'a été créé.
