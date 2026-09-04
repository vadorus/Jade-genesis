# Jade Genesis — Distributed Node Runtime 0.0.4

Ce runtime transforme progressivement le PC en nœud d'une **même Jade Genesis distribuée**. Il conserve le même `node_id`, le même port et le même jeton que le Node Agent 0.0.3 via `%USERPROFILE%\.jade-genesis\node-agent.json`.

## Windows

Aucune dépendance Python externe n'est nécessaire.

```powershell
py jade_node_agent.py
```

ou depuis la racine du dépôt :

```powershell
py node-agent\jade_node_agent.py
```

Le runtime expose :
- `GET /health` : profil matériel et état du nœud ;
- `POST /task` : première exécution distante bornée.

En 0.0.4, **seule** la tâche `genesis_probe` est autorisée. Elle effectue un calcul SHA-256 borné pour valider le routage, le retour de résultat et le fallback. Aucune commande système arbitraire n'est exposée.

Dans Jade Android, le Task Router choisit automatiquement le nœud d'exécution selon le Resource Governor et l'état des nœuds. Si l'exécution distante échoue, Jade revient au téléphone.
