# Coordination de développement multi-IA — v1 (14 octobre 2026)

**Portée :** ChatGPT/Codex, Claude/Claude Code et Google Antigravity. Ce document est une proposition en PR brouillon : il ne remplace aucune règle de dépôt existante tant qu'il n'est pas validé.

## Principe
GitHub sert de mémoire de travail commune, **pas** de canal de communication temps réel. Les agents travaillent lorsqu'ils sont lancés par l'utilisateur. Une IA ne peut pas déduire qu'une autre travaille sur un fichier simplement parce qu'elle a accès au même disque : il faut lire les tickets et les PR.

### Avant chaque nouvelle tâche (obligatoire)
1. Identifier le **bon dépôt et sa branche produit** (voir `AI_START_HERE.md`). Lire `AGENTS.md` et les documents de reprise lorsqu'ils existent.
2. Consulter les issues **et toutes les PR ouvertes, y compris en brouillon**, ainsi que le dernier historique de commits sur la branche produit.
3. Vérifier si le problème est déjà traité, déjà fusionné, ou réservé à un autre agent. Une PR existante fait office de réservation, même si aucune issue distincte n'a été créée.
4. Identifier le ticket / PR de référence, l'agent auteur unique, l'agent relecteur, la zone de fichiers, la base, les tests et les interdictions.
5. Sans attribution explicite : **lecture seule**. Proposer une tâche sans créer de branche ni modifier le code.
6. Toujours travailler dans un **worktree/dossier isolé**. Ne jamais partager le même dossier Git avec un autre agent écrivain ; ne jamais écraser des modifications locales.
7. Si deux tâches touchent les mêmes modules, **bloquer et demander l'arbitrage humain**. Ne jamais résoudre en créant silencieusement une autre implémentation.

### États d'une tâche
`À qualifier` → `Réservée` → `En cours` → `En revue` → `Validée` → `Intégrée`, ou `Bloquée`.
L'état est noté dans l'issue ou la PR, avec responsable et horodatage. Avant de reprendre un ancien ticket, vérifier si l'affectation est encore valable.

### Répartition indicative (non exclusive)
- **Claude Opus :** décisions complexes, architecture, revue de risques et corrections les plus délicates.
- **Claude Sonnet :** implémentation circonscrite après conception, tests et petites revues.
- **ChatGPT/Codex :** cadrage, analyses indépendantes, petites modifications testables et contrôle des régressions.
- **Antigravity :** audits locaux, travaux Windows/Godot/Roblox ciblés, tâches isolées non déjà prises.
Une tâche a **un seul auteur écrivain** ; la revue est faite par **un autre agent**. Ne pas gaspiller Opus pour du travail répétitif.

### Autorisations et sécurité
- Les règles de `AGENTS.md` et les instructions expresses de l'utilisateur prévalent.
- Aucun commit, push, merge, rebase de branche commune, release, APK, publication Roblox, intervention sur VPS ou modification de données sans autorisation adaptée à l'opération. Le présent document **n'accorde aucune permission d'écriture**.
- La création de cette branche documentaire a été autorisée séparément ; elle ne vaut pas autorisation d'implémenter une fonctionnalité.
- Aucune sauvegarde personnelle, aucune donnée DataStore de production et aucun secret d'authentification ne sont modifiés ou publiés.
- Les tests doivent être décrits précisément : commande, version, environnement, résultat ; marquer explicitement les vérifications NON effectuées.

### Passation obligatoire avant revue
Dans l'issue/PR concernée ou le rapport local prévu par le dépôt :
- ID ticket, dépôt, base exacte (SHA), branche, auteur et relecteur.
- Problème reproduit et références des travaux existants réutilisés.
- Changements et **liste des fichiers** ; décisions et raisons.
- Tests réellement exécutés, résultats et sorties utiles, limites.
- Risques, sauvegardes / secrets vérifiés, actions humaines et prochaines étapes.
- **Aucun PASS sans preuve ; aucune fusion automatique.**

### Intégration
Attribution explicite → copie isolée → implémentation et tests → PR brouillon si autorisée → revue indépendante → tests sur appareil si nécessaire → approbation utilisateur → fusion séparément autorisée.

### Coordination entre dépôts
- Godot : https://github.com/vadorus/addonhardwaregame2d
- Roblox : https://github.com/vadorus/tech-empire-roblox
- Jade : https://github.com/vadorus/Jade-genesis

Les notes datées dans `AI_START_HERE.md` ne sont **pas** un inventaire automatique. Rafraîchir GitHub avant toute nouvelle action.
