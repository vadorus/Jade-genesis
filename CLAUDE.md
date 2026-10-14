# Consignes permanentes — coordination multi-IA

Avant **toute action** sur ce projet :
1. Lire `AGENTS.md` s'il existe, puis `docs/AI_START_HERE.md` et `docs/AI_COORDINATION.md`. Ces documents complètent les consignes métier et ne les remplacent jamais.
2. Relever la branche produit réelle, l'état Git local et **les issues/PR ouvertes, y compris brouillons**, ainsi que les travaux récemment fusionnés. Si GitHub ou le précontrôle est inaccessible, rester **en lecture seule**.
3. Pour toute tâche d'écriture, exiger une attribution explicite de l'utilisateur ou une issue GitHub nommant un auteur écrivain unique et la zone réservée. En cas de chevauchement, se déclarer bloqué et demander arbitrage.
4. Utiliser une copie ou un `git worktree` indépendant par agent ; préserver les modifications locales, sauvegardes et secrets.
5. Ne jamais effectuer de commit, push, fusion, publication, modification de sauvegarde ou de machine distante sans autorisation explicite adaptée ; les documents ne créent aucune autorisation.
6. Livrer une passation vérifiable : objectif/issue, auteur, branche et SHA de départ, fichiers modifiés, tests réellement exécutés, tests non faits et relecture attendue. Modèle : `docs/AI_TASK_TEMPLATE.md`.

Si ces documents n'existent pas dans la branche ouverte : **ne pas inventer leur contenu** ; demander la bonne branche ou une instruction explicite.

Ce système coordonne via GitHub ; il ne connecte pas les sessions IA en temps réel.

## Précontrôles et portée réelle

Lire `docs/AI_ENFORCEMENT.md` avant écriture. L’inventaire et un contrôle CI réussi ne créent aucune autorisation, aucun verrou et aucune preuve de revue indépendante. Vérifier à nouveau les réservations avant reprise et avant fusion. Antigravity dispose aussi de `.agents/rules/coordination.md` (`trigger: always_on`).
