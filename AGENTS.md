# Jade Genesis — consignes des agents

Ce fichier est la **seule source** des consignes. `CLAUDE.md`, `GEMINI.md` et la règle Antigravity ne font que renvoyer ici. Le garder court : une règle à un seul endroit.

## Lire au démarrage (rien d'autre)
1. Ce fichier.
2. `docs/ETAT.md` : mission, principes, décisions et leurs raisons.
3. L'issue de ta tâche, puis le tableau de bord **Jade #93** et les étiquettes `orch:*` pour savoir ce qui est déjà pris.

À lire **seulement quand c'est utile** :
- `docs/AI_ENFORCEMENT.md` : avant de réserver des zones ou d'ouvrir une PR.
- `docs/AI_COORDINATION.md` et `docs/AI_TASK_TEMPLATE.md` : en cas de doute sur le protocole ou pour rédiger une passation.

## Règles qui ne bougent jamais
- **Un seul écrivain par tâche**, un relecteur **différent**. Une PR ou une issue `Réservée` / `En cours` / `En revue` réserve ses `Zones`. Chevauchement : s'arrêter et demander l'arbitrage d'Alexandre.
- **Aucune fusion, aucun déploiement, aucun build signé, aucun appel d'API payant** sans l'accord d'Alexandre. **Seul Alexandre écrit `/autoriser`** : aucune IA ne le publie, ne le prépare dans un commentaire ni ne le recopie.
- Travailler sur une branche candidate (`jade/**`, `codex/**`, `claude/**`, `antigravity/**`), jamais directement sur `main`, et ne pas toucher `.github/**`, aux clés ni aux secrets.
- Ne jamais publier de token PC/VPS, de secret GitHub ni d'ancienne clé privée.
- Ne pas modifier le Pixel, le PC Home, le VPS (port 8765 réservé à un autre service), les sauvegardes, Tailscale ou les fichiers de production sans autorisation explicite. Une connexion disponible n'est pas une autorisation.
- Un CI vert ne prouve pas un build : vérifier compilation réelle, version de l'APK et signature.
- Passation : tests **réellement** exécutés, tests non faits, fichiers modifiés, SHA. Aucun PASS sans preuve.

## Qui relit quoi
**Relecture Claude obligatoire** pour : mémoire et apprentissage (Skills, ledger, SEALED_TEST, Night Cycle), sécurité, réseau et nœuds, signature et secrets, workflows, `orchestration/`, migrations de données, et tous les fichiers de consignes (`AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `docs/AI_*.md`, `docs/ETAT.md`).

**Relecture Gemini possible** (quand Claude n'a plus de quota) uniquement pour la documentation et les tests à faible risque, dans les limites de `orchestration/README.md` (phase A).

## Garder l'état à jour
- **Ce qui est en cours** vit dans le tableau de bord #93 et les étiquettes `orch:*`, pas dans un fichier.
- **Une décision d'Alexandre** s'ajoute à `docs/ETAT.md` avec sa raison, en une ligne. On ne modifie `docs/ETAT.md` que dans une PR dédiée, pour ne pas bloquer les autres PR sur ce fichier.
