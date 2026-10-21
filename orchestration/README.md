# ORCH-MVP — répartition multi-IA, prototype isolé

Ce premier module prépare une **proposition** de distribution du travail entre **Claude** (architecte et relecteur), **Codex** et **Antigravity/Gemini** (implémentation). Il ne lance pas les clients IA, ne crée/modifie ni issue ni PR, n'effectue aucun commit et ne consomme aucune API de modèle.

## Tester le code

Depuis la racine du dépôt :

```bash
python3 -m unittest discover -s orchestration -p 'test_*.py' -v
python3 orchestration/dispatcher.py --state orchestration/example_state.json
```

Le fichier exemple est volontairement **non autorisé**, avec quotas inconnus. Il doit retourner un blocage : ne jamais traiter les valeurs exemples comme les quotas ou l'accord réels.

## Lire les réservations GitHub (lecture seule)

```bash
python3 orchestration/dispatcher.py --state orchestration/example_state.json --github
```

`--github` récupère les issues ouvertes de confiance et les fichiers des PR ouvertes du ou des dépôts inscrits dans les tâches. Sur les dépôts privés et si nécessaire pour les limites d'API, utiliser un jeton à droits minimaux exposé via `GITHUB_TOKEN` **dans l'environnement**, jamais dans le JSON ni dans un commit.

La lecture GitHub est **bloquante** en cas de données mal formées de confiance, de réponse incomplète, d'échec réseau/API ou de PR trop volumineuse. Les issues publiques créées par des inconnus ne réservent pas les fichiers du planificateur ; les utilisateurs supplémentaires explicitement autorisés doivent figurer dans `trusted_issue_authors`. Le contrôle officiel du dépôt demeure celui de la branche principale, pas ce prototype.

## Protocole

- Une tâche contient un ID, un dépôt, des zones, son type, une priorité, un coût approximatif et `authorized`. Sans autorisation humaine explicite, elle n'est pas attribuée.
- Chaque agent fournit **manuellement** `status` et `quota_units`. Inconnu (`null`) signifie **non planifiable**, pas « quota illimité ». Les quotas sont des unités de planification, pas des mesures de fournisseurs.
- Les zones se comparent par composants, sans distinction de casse et après normalisation Unicode NFC. Une zone réservée par une autre issue ou PR bloque l'attribution.
- Une tâche de sécurité/architecture va à Claude, revue par Codex. Une tâche d'implémentation/test/documentation va à Codex ou Antigravity, revue par Claude. Un agent auteur ne se relit jamais lui-même.
- Les attributions d'une même simulation réservent immédiatement leurs zones et débitent le budget des agents ; les dépendances non terminées bloquent.
- La sortie est **toujours** un `proposal_only`, jamais un ordre exécuté ou une preuve d'approbation. Elle ne fournit ni verrou atomique, ni validation de tests, ni permission GitHub.

## Ce qui manque pour une orchestration autonome réelle

Cette PR **ne connecte pas** Claude Opus, Codex ou Antigravity à un ordonnanceur actif. Il reste à faire valider le contrôle de confiance des PR #88/#100/#8, fiabiliser le stockage central des réservations, définir des états persistants et des heartbeats, puis ajouter des adaptateurs autorisés pour chaque agent avec budget/quota effectivement observés, passations et reprise après panne. L'application GitHub dédiée et la gestion des droits restent des étapes indépendantes. Aucun lancement de Claude, aucun déploiement, aucune fusion automatique.

Ticket de préparation : #90. Auteur : Codex ; relecture indépendante prévue : Claude. Aucun fichier de produit, sauvegarde ou configuration machine n'est touché.
