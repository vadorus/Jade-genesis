# ORCH-B — Registre GitHub persistant (PR #94)

**Mode par défaut : simulation sans écriture.** Ce module ne lance aucun agent, ne crée
pas de workflow, ne fusionne rien et ne publie pas de commande d'autorisation humaine.

## Préconditions

La branche de données **orchestration/state** est une branche Git **orpheline**,
jamais une copie de main. Elle a été créée le 21/10/2026 avec un **commit racine sans
parent** (`623a8cb5403998721f68159a1883f721222aca13`) contenant uniquement
`registry/README.md` (fichier d'initialisation immuable, ignoré dans l'état des tâches).
La liste blanche des transitions n'autorise PAS à modifier ce README.

Les fichiers de travail appartiennent à la PR de code sur
**codex/orchestration-registry**, issue #94, Zone: orchestration/. Les données sont
stockées séparément sur la branche état :

- registry/tasks/<owner>__<repo>__<issue>.json
- registry/agents/<agent>.json
- registry/history/<task>/<timestamp>-<agent>-<id>.json — append-only

Toute écriture est limitée à ces chemins et à **orchestration/state**, et nécessite
**--apply** ainsi qu'un **GITHUB_TOKEN** disponible uniquement dans l'environnement.
Les autorisations humaines sont relues depuis les commentaires GitHub de l'issue.
Seul Alexandre publie les commentaires d'autorisation, jamais cet outil.

## Commandes

Depuis la racine du dépôt, une fois la branche orpheline initialisée :

    python3 orchestration/registry.py status
    python3 orchestration/registry.py heartbeat Codex --status available --quota 5
    python3 orchestration/registry.py claim '#94' Codex --branch codex/orchestration-registry
    python3 orchestration/registry.py renew '#94' --agent Codex
    python3 orchestration/registry.py handoff '#94' --agent Codex --head-sha <sha_git_40_hex>
    python3 orchestration/registry.py release '#94' --agent Codex
    python3 orchestration/registry.py recover

Les commandes ci-dessus hors status **simulent** leurs modifications.
Pour modifier les données, ajouter --apply avant la sous-commande et fournir
GITHUB_TOKEN avec les droits minimaux nécessaires (dépôt privé : contents read/write).
Pour une tâche d'un autre dépôt : --repo owner/repo, ou owner/repo#123.

Une tâche en `claimed` ou `in_progress` possède un bail de 2 h.
Après `handoff`, la tâche en `review` conserve sa réservation **sans expiration**
jusqu'à une décision explicite ou `release` ; `recover` ne la touche pas.
Un heartbeat de plus de 6 h rend l'agent hors ligne. Les quotas sont
**déclaratifs** et ne constituent pas une mesure des abonnements fournisseurs.

## Sérialisation et reprise

Chaque commande modifiante lit HEAD + l'arbre complet et les blobs du registre.
Elle construit un nouveau tree, puis un commit ayant le HEAD lu comme **parent unique**.
La transition ne devient réelle qu'à l'issue d'un **PATCH sans forçage** de la ref
orchestration/state. Deux commits frères créés sur le même ancien parent ne peuvent
pas tous les deux avancer cette branche : GitHub refuse le deuxième par non-fast-forward.
Un refus 409/422 provoque une relecture de l'arbre, une **nouvelle vérification**
des zones/autorisations/disponibilités, puis une nouvelle tentative ou un abandon.

Les événements sont chacun ajoutés sous un nouveau chemin history ; ils ne sont
jamais mis à jour. Le registre conserve branch, base_sha et head_sha lors du passage
à interrupted. recover transforme **une seule tâche expirée par commit** ; relancer
recover pour traiter les suivantes. Le prochain claim incrémente attempt.

L'inventaire de toutes les PR et des issues est relu avant un claim, avec contrôle
des champs changed_files et des réponses paginées. Une erreur ou inventaire incomplet
interrompt la proposition.

## Limites et risques avant utilisation réelle

1. **La branche orpheline est initialisée**, mais les transactions réelles
   `heartbeat` et `claim` nécessitent une validation indépendante et les autorisations
   GitHub présentes sur chaque issue. Le code reste en proposition seule par défaut.
2. La mise à jour sans forçage garantit la concurrence **entre transitions du
   registre** ; elle ne peut pas rendre atomiques les modifications d'issues et de
   PR sur GitHub. Des changements intervenant après leur lecture restent
   possibles. Avant un déploiement autonome, renforcer ce dernier contrôle.
3. Les comptes et quotas ne sont pas observés automatiquement. Les tokens, droits,
   disponibilités et interruptions restent administrés séparément.
4. Le code n'initialise pas la branche, ne supprime pas de fichiers, ne touche pas
   aux protections et ne contacte pas d'IA.
6. L'issue #94 n'a pas encore de commentaire d'autorisation humaine valide ;
   un `claim --apply` doit **refuser** la tâche tant qu'Alexandre n'en a pas
   personnellement publié un. Cet outil ne rédige ni ne publie ces commandes.
5. Les tests de orchestration/test_registry.py utilisent une **fausse API Git**
   dont la ref accepte seulement les descendants directs sans forçage ;
   le banc de tests n'est pas une preuve d'intégration GitHub réelle.

## Vérification indépendante

    python3 -m unittest discover -s orchestration -p 'test_*.py' -v

Tester en particulier : deux threads sur la même issue ; deux issues sur zones
chevauchantes ; refus de ref périmée sans perte de données ; autorisation périmée ;
heartbeat ancien ; simulation sans écritures ; interdiction des chemins hors registry/.

**Passation :** Codex auteur, Gemini testeur, Claude relecteur. Aucun merge ni
déploiement sans accord séparé d'Alexandre.
