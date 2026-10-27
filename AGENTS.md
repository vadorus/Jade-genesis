# Jade Genesis — consignes des agents

Ce fichier est la **seule source** des consignes. `CLAUDE.md`, `GEMINI.md` et la règle Antigravity ne font que renvoyer ici. Le garder court : une règle à un seul endroit.

## Lire au démarrage (rien d'autre)
1. Ce fichier.
2. `docs/ETAT.md` : mission, principes, décisions et leurs raisons.
3. L'issue de ta tâche, puis le tableau de bord **Jade #93** et les étiquettes `orch:*` pour savoir ce qui est déjà pris.

À lire **seulement quand c'est utile** :
- `docs/AI_ENFORCEMENT.md` : avant de réserver des zones ou d'ouvrir une PR.
- `docs/AI_COORDINATION.md` et `docs/AI_TASK_TEMPLATE.md` : en cas de doute sur le protocole ou pour rédiger une passation.

## Avant d'écrire quoi que ce soit
1. **Sans tâche attribuée par Alexandre** (ou issue nommant un écrivain unique et ses `Zones`) : **lecture seule**. Aucune modification, commit, push ni fusion.
2. **GitHub ou le précontrôle indisponible : lecture seule.**
3. Inventorier **toutes les PR ouvertes, brouillons compris**, les issues réservées et les derniers commits de `main`. Une PR existante vaut réservation. Recommencer avant toute reprise et avant fusion.
4. Travailler dans une **copie ou un `git worktree` isolé**, jamais dans le dossier d'un autre agent. Ne jamais écraser des modifications locales ; préserver sauvegardes et secrets.

## Règles qui ne bougent jamais
- **Un seul écrivain par tâche**, un relecteur **différent**. Une PR ou une issue `Réservée` / `En cours` / `En revue` réserve ses `Zones`. Chevauchement : s'arrêter et demander l'arbitrage d'Alexandre.
- **Aucun commit, push, fusion, déploiement, build signé, test APK ni appel d'API payant sans autorisation explicite d'Alexandre**, propre à l'opération. Ni ce fichier, ni un inventaire, ni un CI vert ne crée une autorisation, un verrou ou une preuve de relecture. **Seul Alexandre écrit `/autoriser`** : aucune IA ne le publie, ne le prépare dans un commentaire ni ne le recopie.
- Travailler sur une branche candidate (`jade/**`, `codex/**`, `claude/**`, `antigravity/**`), jamais directement sur `main`, et ne pas toucher `.github/**`, aux clés ni aux secrets.
- **Confidentialité des nœuds, clés et paramètres d'appairage.** Ne jamais publier de token PC/VPS, de secret GitHub ni d'ancienne clé privée, y compris dans une PR.
- Ne pas modifier le Pixel, le PC Home, le VPS (port 8765 réservé à un autre service), les sauvegardes, Tailscale ou les fichiers de production sans autorisation explicite. Ne jamais toucher aux nœuds vivants ni aux données personnelles du Pixel. Une connexion disponible n'est pas une autorisation.
- Si un document cité ici manque dans la branche ouverte : ne pas inventer son contenu, demander la bonne branche.

## Validation
- Tests Python et Android du module touché, contrôle des logs horodatés, non-régression réseau.
- Test de l'APK sur appareil **seulement avec accord et sauvegarde**. Un CI vert ne prouve pas un build : vérifier compilation réelle, version de l'APK et signature.
- Passation : tâche, auteur, relecteur, branche et SHA de départ, fichiers modifiés, tests **réellement** exécutés, tests non faits. Aucun PASS sans preuve. Modèle : `docs/AI_TASK_TEMPLATE.md`.

## Qui relit quoi
Le relecteur est toujours celui nommé dans l'issue, et jamais l'auteur.

**Relecture Claude obligatoire** pour : mémoire et apprentissage (Skills, ledger, SEALED_TEST, Night Cycle), sécurité, réseau et nœuds, signature et secrets, workflows, `orchestration/`, migrations de données, et tous les fichiers de consignes (`AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `docs/AI_*.md`, `docs/ETAT.md`). **Quand Claude en est l'auteur**, c'est Codex qui relit.

**Relecture Gemini possible** (quand Claude n'a plus de quota) uniquement pour la documentation et les tests à faible risque, dans les limites de `orchestration/README.md` (phase A).

## Garder l'état à jour
- **Ce qui est en cours** vit dans le tableau de bord #93 et les étiquettes `orch:*`, pas dans un fichier.
- **Une décision d'Alexandre** s'ajoute à `docs/ETAT.md` avec sa raison, en une ligne. On ne modifie `docs/ETAT.md` que dans une PR dédiée, pour ne pas bloquer les autres PR sur ce fichier.
