# JADE-HALT / NET-GUARD V0 — arrêt coopératif local

**Issue #71** · Auteur-IA : Codex · Relecteur-IA : Claude · Branche `jade/halt-netguard-v0`.

## Garanties de V0

- État `RUNNING` ou `HALTED`, conservé sous `JADE_GENESIS_CONFIG_DIR/jade-halt.json` avec motif, origine et horodatage. Verrou **réel** `shared_store_lock.shared_path_lock`, écriture **réelle** `atomic_write_text` et copie de sauvegarde par `atomic_copy_file`. Journal JSONL local en ajout seul.
- Un fichier absent signifie `RUNNING`. Un fichier présent mais illisible ou corrompu signifie **`HALTED`** avec `halt_state_unreadable` ; la sauvegarde ne réarme jamais automatiquement.
- `halt(reason, source="MANUAL" ou "AUTO:<détecteur>")` peut uniquement arrêter. `rearm(explicit_approval=True, operator=..., reason=...)` est la seule API de réarmement ; une action automatique ne peut pas reprendre l'activité. Cette API ne prouve pas l'identité cryptographique de l'humain : appel réservé à une interface d'administration contrôlée, hors V0.
- Une interruption produit `halt_blocked` à l'entrée ou `halt_interrupted` à un point de contrôle intermédiaire, avec nom de boucle et étape. Le statut de l'étape est `INTERRUPTED_BY_HALT`, jamais succès, échec, `FAILED_APPROACH` ou résultat candidat statistique. Les exceptions de HALT sont distinctes des exceptions de travail, pour ne pas être converties en erreur par les `except Exception`.
- Les quatre zones contrôlées sont `adaptive_vps_night_cycle.py` (avant `super().run_once()`, entre phase de base, persistance stratégique et manivelle Skill), `night_learning_lab.py` (avant **chaque** appel de recherche web), `skill_synthesis_loop.py` (itération et évaluation visibles) et `skill_learning_cycle.py` (avant chaque grande étape et chaque appel à l'enseignant).
- Déclencheurs fournis : `network_guard_violation`, appels répétés non autorisés au `SEALED_TEST`, échecs protégés consécutifs. Seuils configurables et bornés de 2 à 20, seuil d'échecs par défaut 3. Les signaux déjà visibles d'échec final `SEALED_FAILED` sont comptabilisés après retour de `run_attested_learning`. Les chemins hors zones réservées ne sont **pas** instrumentés : la détection d'accès SEALED_TEST n'est donc pas exhaustive.

## Portée et garanties non fournies

**L1 — Pas d'interruption forcée.** Une requête HTTP, l'appel à un modèle ou une phase atomique déjà engagée peut se terminer avant que le prochain point de contrôle observe `HALTED`. La latence dépend de la durée/du timeout d'une étape. Pour un arrêt immédiat, l'opérateur doit arrêter le service, action manuelle **hors V0**.

**L2 — Portée locale seulement.** L'état HALTED d'un VPS ne coupe ni le PC ni le Pixel. Propagation V1 : un arrêt peut se propager entre nœuds ; jamais de réarmement automatique ; un nœud injoignable conserve son état local. `shared_genesis_state.py` et `jade_node_runtime_core.py` ne sont pas modifiés (PR #51 en cours).

**L3 — Couverture partielle.** Les points d'entrée suivants ne sont **pas** couverts par l'arrêt global V0 : `goal_source_research.py`, `official_source_capture.py`, `free_capability_discovery.py`, le serveur de `research_review`, et le canary d'évolution Android. `vps_night_cycle.py` n'est pas modifié : son chemin de base reste un point d'entrée distinct si appelé directement, hors superviseur adaptatif. Les protections d'arrêt à l'intérieur de `ResilientSkillSynthesisLoop` (hors réservation) ne sont que partiellement couvertes via l'enseignant injecté et l'évaluation héritée.

**L4 — Administrateur local.** Toute personne qui contrôle les fichiers ou le processus peut modifier l'état. Le journal JSONL est un audit en ajout seul *applicatif*, pas un stockage immuable ni un journal cryptographiquement infalsifiable. L'écriture atomique protège de l'écriture partielle, pas de deux processus indépendants écrivant simultanément : `shared_path_lock` est un verrou au niveau processus.

**Limite de transaction.** Un arrêt survenant après la validation d'une phase ne peut pas annuler les données de cette phase déjà persistées. Les étapes suivantes ne démarrent pas ; les résultats d'étape non terminée ne sont pas ajoutés au bilan de l'adaptateur. Le cycle de base peut déjà avoir écrit son journal avant que l'adaptateur observe l'arrêt ; une atomicité globale de tout le cycle nécessiterait modifier `vps_night_cycle.py` (interdit dans #71). V0 ne prétend donc pas fournir un rollback transactionnel multi-modules.

## Garde réseau Python — détection, PAS isolation

Le contexte `no_python_network()` intercepte `socket.socket`, `socket.create_connection`, `socket.getaddrinfo`, `socket.gethostbyname`, `gethostbyname_ex` et `getnameinfo`; il restaure **exactement** toutes les fonctions, même sur exception. Chaque violation lève `NetworkGuardViolation`, avec nom d'opération seulement : ni domaine, ni URL, ni jeton, ni SEALED_TEST dans les traces.

Les runners déclarés `NO_NETWORK_CALLS_IN_RUNNER` de `local_research_review.py` tournent dans un **processus enfant** via `multiprocessing.get_context("spawn")`. Le parent conserve son `ThreadingHTTPServer` sans remplacement global de socket. Une violation revient au parent, produit `network_guard_violation`, passe localement à `HALTED` et entraîne une erreur de runner. Le DSL déterministe de `procedure_runtime.py`, qui ne contient pas d'opération réseau, bénéficie de la garde uniquement en mode mono-thread ; en contexte serveur multithread, le patch global est explicitement refusé.

**Ce n'est pas un bac à sable.** Le patch Python n'empêche **ni** un sous-processus (y compris Blender), **ni** du code natif, **ni** un accès réseau hors des fonctions patchées. Avant d'autoriser l'exécution de code autonome `FUTURE_SYNTHESIS` ou de challengers **N5**, l'opérateur DOIT mettre en place une **isolation réseau au niveau système** : utilisateur/processus dédié avec règles de pare-feu sortantes explicites, ou conteneur/espace de noms réellement sans réseau. **Sans cette isolation, exécution de code généré interdite.** Cette V0 n'ajoute aucune dépendance, aucun conteneur et aucune activation de code N5.

## Validation requise avant fusion

Sur copie isolée, sans réseau réel, lancer :

```bash
python scripts/ai_preflight.py --repo vadorus/Jade-genesis --base main --task 71 --writer Codex --reviewer Claude \
  --path node-agent/jade_halt.py --path node-agent/test_jade_halt.py \
  --path node-agent/network_guard.py --path node-agent/test_network_guard.py \
  --path node-agent/adaptive_vps_night_cycle.py --path node-agent/night_learning_lab.py \
  --path node-agent/skill_synthesis_loop.py --path node-agent/skill_learning_cycle.py \
  --path node-agent/local_research_review.py --path node-agent/procedure_runtime.py \
  --path docs/JADE_HALT_V0.md
python -m unittest discover -s node-agent -p 'test_*.py' -v
```

Les tests synthétiques vérifient blocage des quatre entrées, un seul appel web après interruption, persistance/corruption, limites de réarmement, déclencheurs, restauration des fonctions et processus enfant `spawn` sans réseau réel. Les résultats ne sont attestés qu'après leur exécution effective sur le SHA de PR. **Pas de Pixel, pas de VPS/PC vivant, pas de fusion ni de build signé.**
