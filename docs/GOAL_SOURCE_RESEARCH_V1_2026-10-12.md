# Jade Genesis — Recherche guidée par objectif V1 (12 octobre 2026)

## Périmètre
Développé dans la copie isolée `C:\Users\alexa\Documents\JadeGenesis-MemoryAudit-20261012`, à partir de `vadorus/Jade-genesis` HEAD `f486452`. Pas de commit, push, merge, déploiement ou réinstallation de Jade personnelle.

## Travail intégré dans le dépôt
- `node-agent/official_source_capture.py` : fonction de récupération contrôlée qui peut retourner temporairement le corps HTML en mémoire et les métadonnées SHA-256 ; comportement existant `capture_one()` conservé.
- `node-agent/goal_source_research.py` : choix automatique, mais uniquement par règles et mots-clés, d'un sous-ensemble des 3 sources techniques déjà autorisées. Téléchargement HTTPS borné, extraction HTML sans scripts ni navigation, sélection des paragraphes pertinents, empreintes SHA-256 par page et extrait, drapeaux de restrictions potentielles. Aucune affirmation n'est déclarée vraie ; les données documentaires externes sont non fiables.
- `node-agent/night_learning_lab.py` : deux nouvelles entrées `plan_official_goal_research(goal)` (hors réseau) et `research_official_goal(goal, approved=True)` (collecte limitée avec indicateur d'autorisation explicite fourni par l'appelant). Ces fonctions ne sont PAS invoquées automatiquement par la supervision nocturne.
- `node-agent/test_goal_source_research.py` : 17 tests dont choix de sources, absence de réseau pour domaine inconnu, pages injectant du HTML/script, hash et provenance, panne réseau, fausses confirmations, restrictions et contrôle d'autorisation.
- `.gitignore` : ignore `capture_local/` pour éviter la publication accidentelle des expériences locales.

## Essai documenté
Objectif : « Comment exporter un arbre 3D depuis Blender vers Roblox Studio en FBX avec des textures et des matériaux ? »
- 3 documents officiels sélectionnés et récupérés : Roblox Blender, Roblox export requirements et Blender FBX manual.
- 15 passages documentaires référencés par URL, SHA-256 et index de bloc.
- 1 restriction potentielle détectée dans les passages les plus pertinents du document Roblox Blender, sans prétendre qu'il s'agit d'une contradiction démontrée.
- Aucune erreur de téléchargement ; aucune exécution de texte externe ; statut général `automatic_verification=false`, `automatic_promotion=false`.
- Rapport JSON local : `capture_local/goal_research_fbx_v2_20261012.json`. Les captures précédentes restent dans le dossier local ignoré par Git.

## Tests
- Suite Python node-agent : **164 réussis / 164**, sans échec.
- Compilation et suite Android inchangée : `:app:testDebugUnitTest` réussie (les 235 tests précédemment relevés sont à nouveau au vert ; tâche Gradle UP-TO-DATE).
- `git diff --check` : à contrôler en fin de lot.

## Sécurité / limites réelles
- Catalogues statiques de trois pages pour ce premier domaine : ce n'est pas une découverte illimitée de nouveaux sites ni une recherche sémantique générale.
- Les pages publiques sélectionnées sont de vrais documents consultés ; les extraits sont filtrés lexicalement, non inspectés intellectuellement.
- Un passage « limitation possible » est une piste de contre-vérification, pas une preuve de contradiction.
- L'indicateur `approved=True` protège l'API, mais la future UI devra prouver le consentement effectif avant l'appel.
- Le Night Cycle automatique continue à utiliser uniquement les agrégats de la topologie Jade ; il n'est PAS autorisé à exfiltrer l'objectif privé, lancer la collecte ni promouvoir des connaissances.
- La nouvelle chaîne Python n'est pas encore reliée à la conversation Android, au transport Mesh, au registre `NightEvidenceLedger`, ni à une boucle d'apprentissage autonome complète.
- Aucun test Roblox Studio réel dans ce lot ; les expériences FBX Blender antérieures restent limitées à Blender.

## Reprise recommandée
1. Créer un canal de tâches de recherche approuvées, avec autorisation traçable et séparation stricte des données privées et publiques.
2. Ajouter des catalogues de sources vérifiables sur d'autres domaines, avec contrôles d'hôte et redirections, sans découverte libre dangereuse.
3. Faire relire les extraits par un raisonneur avec contre-recherche indépendante et preuves contradictoires ; ne pas marquer « vérifié » par simple correspondance textuelle.
4. Importer des résultats de tests réels et attestables (Blender/Studio dans une copie isolée) dans `NightEvidenceLedger`, via relecture humaine.
5. Ajouter des tests de sélection adversariale, de vieillissement des sources, et des validations conversationnelles sur la variante Android TEST sans toucher à l'application personnelle.
