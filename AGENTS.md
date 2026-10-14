# Jade Genesis — consignes des agents

Respecter la confidentialité des nœuds, clés et paramètres d'appairage. Ne pas modifier le Pixel, le PC Home, le VPS, les sauvegardes, le réseau Tailscale ou les fichiers de production sans autorisation explicite. La présence d'une connexion n'autorise pas les actions sensibles.

## Coordination multi-IA (Claude, Codex, Antigravity)

Lire `docs/AI_START_HERE.md` et `docs/AI_COORDINATION.md` avant toute tâche. Vérifier les issues et PR en cours, y compris les brouillons, **avant de réserver un chantier**. Une tâche = un seul agent écrivain + un autre relecteur. Sans autorisation claire, pas de modification, commit, push ou fusion. Si l'accès GitHub ou l'inventaire est indisponible, rester en lecture seule. Les consignes métier et les interdictions du pilote Agents API ci-dessus restent prioritaires.

## Précontrôles et portée réelle

Lire `docs/AI_ENFORCEMENT.md` avant écriture. L’inventaire et un contrôle CI réussi ne créent aucune autorisation, aucun verrou et aucune preuve de revue indépendante. Vérifier à nouveau les réservations avant reprise et avant fusion. Antigravity dispose aussi de `.agents/rules/coordination.md` (`trigger: always_on`).
