# JADE-PROGRESS V0 — rapport hebdomadaire en lecture seule

**Issue #75. Auteur : Codex. Relecteur : Claude.**

## Commandes

Depuis la racine du dépôt (Python 3.10+) :

    JADE_GENESIS_CONFIG_DIR=/var/lib/jade-genesis/config python3 node-agent/jade_progress.py report
    JADE_GENESIS_CONFIG_DIR=/var/lib/jade-genesis/config python3 node-agent/jade_progress.py report --days 7 --json

Fenêtre par défaut : 7 jours révolus, comparés aux 7 jours précédents.
Bornes exprimées en millisecondes UTC, intervalle fermé-ouvert [start_ms, end_ms).
Jours autorisés : 1 à 365.

Le JSON contient schema_version=1, generated_at_ms, days, current, previous, delta,
halt_state et warnings. Chaque fenêtre contient start_ms, end_ms et metrics.
Les différences = valeur courante - valeur précédente. Null veut dire
« preuve indisponible » et non zéro.

## Sources existantes lues

Racine : JADE_GENESIS_CONFIG_DIR (sinon ~/.jade-genesis).

- workshop/skill-learning-goals.json : created_at, updated_at, candidate_count.
- archive/verifiable-task-ledger.json : uniquement les dates et verdicts
  des examens finaux datasets.*.sealed_exam ; jamais les cases SEALED_TEST.
- archive/skill-attribution.jsonl : uniquement event_kind, occurred_at.
- adaptive-strategy-registry.json : historique des changements (action, at).
- night-cycle-supervisor.json : runs, statuts et codes de phases échouées.
- jade-halt.json : état courant RUNNING / HALTED.
- jade-halt.jsonl : événements HALT et de garde réseau.

Le module ne construit pas les classes de stockage existantes : leurs méthodes
de lecture peuvent restaurer des sauvegardes, migrer ou écrire. Ici : ouverture
des fichiers strictement en lecture, aucune migration, aucun verrou en écriture,
aucun appel modèle/réseau, aucun accès aux données internes des cas SEALED_TEST.
Les warnings ne contiennent que des codes et des noms de chemins prédéfinis.

## Interprétation des indicateurs

SKILLS : goals_opened = objectifs créés dans la fenêtre ; candidates_proposed
= somme des candidate_count des objectifs entièrement observables dans cette
fenêtre. teacher_calls reste null avec l'avertissement teacher_calls_not_recorded :
aucun compteur réel des requêtes au professeur n'est conservé par ces sources.
Le nombre de candidats ne sert jamais de substitut au nombre d'appels.
Les tentatives ne sont pas horodatées individuellement : si un objectif déborde
sur une autre période, candidates_proposed est null pour toute la fenêtre, plutôt
que de réattribuer à tort l'ensemble des tentatives. Avec des objectifs étalés
sur plusieurs jours, cette limite sera fréquente ; elle est signalée par
weekly_candidate_attempts_not_reconstructible. sealed_passed/failed
comptent les examens finaux uniques (et pas les cas de test individuels).
skills_retained compte les événements d'archivage.

STRATEGIES : candidates = INGESTED ; validated = VALIDATED ; activated =
PROMOTED ; rolled_back = ROLLED_BACK. retired est inféré d'une promotion avec
previous_active_strategy_id non vide (ancienne stratégie désactivée). Ces
valeurs comptent des TRANSITIONS sur la période, pas un inventaire instantané.
L'historique du registre est borné : des transitions anciennes peuvent manquer.

CYCLES : launched/succeeded/partial sont tirés des runs enregistrés.
Un cycle qui plante avant la sauvegarde de son rapport n'est pas compté : son
échec n'existe que dans _last_attempt en mémoire. Un éventuel FAILED enregistré
est compté, sans garantir l'exhaustivité des lancements ou des échecs.
L'avertissement cycle_failures_not_durably_recorded rappelle cette limite.
Le journal conserve au plus 30 runs : une comparaison de deux fenêtres de plus
d'environ 15 jours peut être tronquée même à un seul cycle par nuit.
failed_phases donne le nombre d'échecs par code de phase connu, sans lire ni
restituer les résumés textuels. skipped ne compte que les éventuels enregistrements
SKIPPED visibles. IMPORTANT : le superviseur actuel retourne un état SKIPPED
(motif pixel_active, cooldown, etc.) sans le conserver durablement ; le rapport
ne peut donc pas prouver le nombre total de cycles sautés ni leurs motifs.
L'absence d'enregistrement SKIPPED donne donc skipped=null, et non un faux zéro.
Quand des enregistrements SKIPPED existent, skip_reasons compte uniquement les
motifs du vocabulaire connu (motif inconnu => other, pour éviter toute fuite).
Ce nombre est une observation partielle, pas une exhaustivité garantie. Le suivi
persistant des sauts exige une autre issue et des modifications hors des 3 fichiers réservés.
interrupted_by_halt est le nombre d'événements halt_interrupted de la boucle
adaptative, sans preuve d'unicité de cycle (pas de run_id dans ces événements).

SECURITY : halt_state est un état actuel, hors comparaison temporelle ;
halt_transitions et network_guard_violations comptent les événements horodatés.
Le rapport ne lance jamais halt, rearm ou une opération de déploiement.

HALT est une exception à la règle générale des sources manquantes : absence de
jade-halt.json => RUNNING, comme JadeHalt._load. Fichier illisible, schéma autre
que 1, état invalide, reason/source non textuels, compteur ou date manquant,
non entier ou négatif => HALTED avec halt_state_unreadable_fail_closed et le
texte « HALTED (état illisible, fail-closed) ». La validation reproduit les
prédicats du runtime, sans appeler JadeHalt.status() ou _load(), sans consulter
la sauvegarde et sans écrire dans jade-halt.jsonl. Les tests de parité utilisent
l'oracle runtime dans un autre dossier temporaire, afin que sa journalisation
éventuelle ne puisse modifier les fichiers lus par le rapport.

Hors cet état HALT, une source absente, illisible ou incompatible donne null pour
les compteurs dépendants, avec un avertissement. Une source disponible mais sans
événement horodaté donne zéro. Les historiques peuvent être plafonnés ou incomplets ;
aucune absence d'événement ne doit être considérée comme une preuve de sécurité
ou d'absence de saut.

## Tests

    python3 -m unittest -v node-agent/test_jade_progress.py
    python3 -m unittest discover -s node-agent -p 'test_*.py' -v
    python3 scripts/ai_preflight.py --repo vadorus/Jade-genesis --base main --task 75 --writer Codex --reviewer Claude --path node-agent/jade_progress.py --path node-agent/test_jade_progress.py --path docs/JADE_PROGRESS_V0.md

Les tests utilisent des magasins synthétiques temporaires : fenêtres et deltas,
sources absentes/corrompues, empreintes avant/après, CLI JSON, secrets et
contenu SEALED_TEST non publiés. Les résultats réellement obtenus seront
documentés séparément dans la PR.

Hors périmètre : aucune modification de jade_node_runtime_core.py,
shared_genesis_state.py ou du Pixel ; aucun déploiement VPS. PR brouillon
et revue indépendante de Claude obligatoires avant décision humaine de fusion.
