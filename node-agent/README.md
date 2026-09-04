# Jade Genesis — PC Node Agent 0.0.3

Ce petit agent permet à Jade Android 0.0.3 de connaître l'état réel d'un PC sur le réseau local.

## Windows

Aucune dépendance Python externe n'est nécessaire.

Depuis la racine du dépôt :

```powershell
py node-agent\jade_node_agent.py
```

Le terminal affiche :
- l'IP locale du PC ;
- le port (8765 par défaut) ;
- le jeton d'appairage ;
- l'identifiant stable du nœud PC.

Lors du premier lancement, Windows peut demander l'autorisation pare-feu. Autoriser le programme sur le **réseau privé**.

Dans Jade Android, ouvrir la carte **Node Manager**, autoriser le réseau local, puis saisir l'IP, le port et le jeton affichés par le PC. Appuyer sur **Enregistrer + tester le PC**.

Le Node Agent 0.0.3 n'exécute aucune commande distante. Il expose uniquement un endpoint `/health` authentifié pour fournir le profil matériel et l'état du PC.

Le Node ID et le jeton sont conservés dans :

`%USERPROFILE%\.jade-genesis\node-agent.json`

Pour régénérer le jeton :

```powershell
py node-agent\jade_node_agent.py --reset-token
```
