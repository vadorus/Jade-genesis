# Jade Genesis — Sources officielles et expérimentation FBX (12 octobre 2026)

## Portée et sécurité
- Copie isolée sur **home** : C:\Users\alexa\Documents\JadeGenesis-MemoryAudit-20261012 (HEAD f486452, aucun commit).
- L'application personnelle sur Pixel reste en version 0.1.22. Les installations de validation portent uniquement le package com.jadegenesis.mobile.validation.
- La version TEST n'a pas la permission INTERNET ; les synchronisations et travaux automatiques ont été désactivés pour elle.
- Aucune modification des jeux Blender/Roblox existants, des sauvegardes, du VPS, ni du dépôt distant.

## Collecteur réel (node-agent/official_source_capture.py)
Capture manuelle et strictement bornée de trois URLs officielles préapprouvées :
1. Roblox export settings : https://create.roblox.com/docs/art/modeling/export-requirements
   - 465935 octets ; SHA-256 : 85bf247ee102f75df990c302363bfe6ec626308711940ab44e1dd939aa89d731
2. Roblox Blender integration : https://create.roblox.com/docs/art/blender
   - 801424 octets ; SHA-256 : d9ca1f3d596084a61e11f2d0a0b6d6c0bdec9581697439362b3af3d6efc26c7b
3. Blender FBX manual : https://docs.blender.org/manual/en/latest/files/import_export/fbx.html
   - 464243 octets ; SHA-256 : 52c83350fc117b405994422cb588e04a5424506e497a9f87c8f8cc0995b96883

Restrictions : HTTPS et URLs exactes, sans redirection ni proxy, sans exécution de HTML, corps <= 1 Mio, délai 10 s, types HTML/texte, aucune copie persistante du corps des pages. La capture reste **UNASSESSED / inspected=false**. L'empreinte protège contre la confusion entre deux versions d'une page, elle n'en prouve PAS l'exactitude.

Manifeste local : capture_local/official_sources_20261012.json.
Dix tests unitaires du collecteur réussis.

## Passage sur Android
- Android reçoit seulement les métadonnées officielles dans OfficialSourceCaptureInbox (trois URL fixes).
- Import, nouvelle exécution et nettoyage sur Pixel : PASS.
- Verdict attendu et observé : NEEDS_EVIDENCE, auto_promoted=false.
- Les trois références du test ont été retirées de l'inbox ensuite.
- Cette passerelle est utilisée manuellement par instrumentation pour validation. Elle n'est pas encore intégrée à l'interface quotidienne ou déclenchée par le Night Cycle.

## Expérimentation 3D contrôlée
Exécutable local : Blender 5.2.2 LTS.
Script : node-agent/blender_fbx_roundtrip_probe.py.
Essai : création d'un simple modèle synthétique (tronc cylindrique + feuillage conique), export FBX, réimport dans Blender.
- Essai 1 : PASS, deux meshes, sommets/triangles/dimensions conservés.
- Essai 2 : PASS, mêmes résultats géométriques.
- Les SHA-256 des deux fichiers FBX sont différents (métadonnées binaires), mais les propriétés géométriques vérifiées sont reproductibles.
- Essai 1 FBX SHA-256 : 35d777a9f16c09a5d7365642d6bec7cc094641edf192680326867af6392a087c.
- Essai 2 FBX SHA-256 : d5c261da0a361499241643418112a56d0792108ff4cbabf818838a4e009a9d1f.
Rapports + FBX : capture_local/fbx_roundtrip_01 et capture_local/fbx_roundtrip_02.
**Limites :** aucun import dans Roblox Studio ; aucune validation du rendu, de la physique, des matériaux, des textures ou de l'optimisation mobile.

## Défaut supplémentaire découvert et corrigé
La suite complète Python révélait un échec dans test_learning_environment : l'échec volontaire de publication d'un case pack laissait un .tmp en lecture seule sous Windows.
Fix : case_pack_archive.py garde le temporaire inscriptible jusqu'à sa suppression, puis applique la lecture seule uniquement sur le nom final publié.
Ajout d'une assertion de non-persistance des .tmp après publication réussie dans test_learning_environment.py.

## Validation
- Tests unitaires Android : 235, zéro échec. APK de validation et APK de tests Android compilés.
- Tests du module Python node-agent : 147, zéro échec après correctif Windows (146/147 avant).
- Pixel : source_import PASS ; source_verify PASS ; source_cleanup PASS.
- Blender : 2/2 exécutions de roundtrip PASS.
- Git diff --check OK à confirmer en contrôle final ; aucun commit / push / merge / déploiement.

## Ce qui manque encore pour un vrai moteur autonome
1. Extraction de passages pertinents des pages publiques avec provenance textuelle, contrôle de cohérence, recherches contradictoires et inspection vérifiable du contenu.
2. Moteur qui choisit lui-même les documentations en fonction d'un objectif et de ses connaissances manquantes (sans inventer un statut vérifié).
3. Transfert contrôlé de résultats expérimentaux au registre NightEvidenceLedger, avec traçabilité de l'environnement et validation indépendante.
4. Intégration approuvée au Night Cycle, politiques de réseau/sources, budgets, et interface de validation humaine.
5. Test d'un import réel dans une copie du projet Roblox Studio, séparée des données personnelles.
