# Reprise locale de la revue des preuves — 13 octobre 2026

Travail effectué sur le PC `HOME`, dans
`C:\Users\alexa\Documents\JadeGenesis-MemoryAudit-20261012`.
Les 58 fichiers déjà modifiés ou non suivis ont été copiés avec leurs empreintes
avant les changements. Aucun reset, commit, push, merge ou déploiement VPS.

## Parcours ajouté

- Le bouton **Télécharger le dossier pour Jade TEST** exporte les passages et les
  octets FBX du dossier réellement affiché. L'export est hors réseau, limité à
  300 000 octets et refusé si le dossier change ou si le journal est endommagé.
  L'API exige la session locale, la même origine et l'empreinte actuelle.
- `NightLearningLab.export_official_goal_review` raccorde l'export existant au
  contrôleur local. La construction du paquet continue d'utiliser
  `goal_evidence_reasoner.py`. Aucune autorisation PC n'est exportée.
- Sur Android, **Expériences** affiche les trois étapes déjà validées par
  `NightResearchProposalInbox` : import Roblox, fidélité des matériaux/textures
  et contre-revue indépendante. La dépendance de l'étape matériaux est visible.
  Les outils PC ne sont pas reliés au téléphone.
- Une source développée permet de consigner une lecture `SUPPORTS`, `CONTRADICTS`
  ou `UNCERTAIN`, avec nom et motif. La décision est liée aux empreintes du
  dossier et de la source, et reste visible après redémarrage. Elle ne crée ni
  autorisation, ni connaissance vérifiée, ni entrée dans `NightEvidenceLedger`.
- La validation du journal Android est maintenant testable hors appareil :
  chaîne des empreintes, import préalable, plan exact, identité/motif,
  transitions, expiration, révocation, consommation avant exécution et absence
  de promotion. Un import dont la trace n'a pas été persistée bloque les
  décisions suivantes jusqu'à réimport. Les nouvelles autorisations conservent
  également la confirmation explicite ; les anciens événements v1 restent lus.
- Les autorisations expirées sont actualisées dans les deux interfaces. Le
  journal Android permet aussi de révoquer une ancienne autorisation en attente.

## Résultats effectivement vérifiés

| Contrôle | Résultat |
| --- | --- |
| Suite Python complète | 235 tests, zéro échec |
| Suite Android unitaire | 270 tests, zéro échec, zéro erreur, 49 suites |
| Compilation Android | APK debug et instrumentation construits hors ligne, `BUILD SUCCESSFUL` |
| Contrôle des modifications | `git diff --check` et syntaxe JavaScript sans erreur |
| Navigateur, dossier réel | 3 sources / 2 FBX affichés ; export téléchargé, 66 506 octets |
| Navigateur, atelier synthétique distinct | Refus sans identité, autorisation sans lancement, rechargement, révocation, autorisation puis lancement séparé, résultat et lecture persistante ; aucune erreur console observée |
| Pixel 10 / Jade TEST | Import identique, 3 sources / 15 passages / 2 FBX, navigation native, autoriser/révoquer/lancer, propositions et dépendance visibles, lecture consignée, redémarrage et non-réutilisation des permissions : PASS |
| Registre des preuves Pixel | Nombre d'entrées inchangé pendant les contrôles |
| Jade personnelle | Empreintes des APK installés, version 0.1.22 et date de mise à jour identiques avant/après ; aucune commande de modification ne la cible |

Les deux APK installés ciblent exclusivement `com.jadegenesis.mobile.validation`
et son instrumentation `.validation.test`. Leur identité a été contrôlée avant
installation. L'APK TEST ne contient ni permission INTERNET ni permission réseau
local. Les données privées de Jade TEST ont été sauvegardées avant mise à jour.
Les données privées de l'application personnelle n'ont pas été ouvertes.

Empreinte de l'export téléchargé :
`6811a45da12d011b97598d9a1cf967faa10248d3110378108d48b0261d043320`.
Ses octets sont identiques au dossier déjà présent sur Jade TEST ; aucun autre
dossier n'a été écrasé. Les décisions historiques ont été conservées.

Deux problèmes de l'instrumentation ont été corrigés pendant la validation :
l'import supposait un journal initialement vide, et un toucher par coordonnées
pouvait seulement arrêter le défilement. Le contrôle final utilise les actions
d'accessibilité natives sur les seuls boutons visibles de Jade TEST. Cela prouve
ce parcours instrumenté, sans prétendre à un essai manuel au doigt.

## Limites maintenues

Aucun nouveau téléchargement de documentation ni essai Blender réel n'a été
lancé. L'import dans Roblox, le rendu et la fidélité des textures ne sont pas
validés. Aucun lien PC/Pixel d'exécution distante, aucune promotion automatique
et aucune recherche nocturne autorisée par ce parcours. Les journaux sont des
traces locales avec contrôles de cohérence, pas des signatures d'identité ni un
stockage inviolable face à un acteur ayant les mêmes droits sur le système.

## Reprise

Pour ouvrir l'interface PC depuis la copie isolée :

```powershell
& 'C:\Users\alexa\AppData\Local\Programs\Python\Python311\python.exe' node-agent\local_research_review.py --port 8768
```

Sur Jade TEST : **Mémoire → Recherches et preuves**. Le dossier, les propositions,
les lectures déclarées et les décisions d'autorisation sont conservés localement.
