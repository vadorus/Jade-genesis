# Atelier général d'amélioration de Jade — 13 octobre 2026

Cette étape recentre le développement sur Jade. Le dossier spécialisé utilisé
précédemment servait de fixture de validation ; il ne définit plus l'objectif
par défaut ni le parcours principal de l'atelier.

## Modifications

- L'interface PC démarre sur **Atelier d'amélioration de Jade**, sans charger un
  scénario de jeu. En l'absence de rapport, elle affiche une attente explicite,
  sans fabriquer d'observations ni de propositions.
- L'import accepte un compte rendu borné de `NightLearningLab` ou un dossier
  `jade.mobile_learning_review.v1`. Les octets du rapport sont inclus et liés à
  une empreinte ; les indicateurs d'exécution et de promotion doivent être
  explicitement désactivés. Les propositions sont liées à leurs hypothèses,
  leurs mesures, leurs critères de réussite et leurs limites d'échantillonnage.
- `NightLearningLab.draft_local_learning_review` prépare ce dossier à partir des
  observations agrégées, sans appeler de fournisseur de recherche externe.
  Le comportement historique de `review` reste disponible ; le nouveau chemin
  local demande expressément une préparation sans recherche externe.
- La revue Android utilise le parseur général de `NightLearningInbox`, puis
  transmet uniquement le résumé provisoire à `NightResearchProposalInbox`.
  Le résumé nocturne parle maintenant des propositions et hypothèses de Jade.
- Les deux interfaces permettent de relire les observations et hypothèses,
  consigner une interprétation et examiner les essais proposés. Les critères
  et tailles de lots sont visibles. Le seul runner relié au nouveau dossier est
  le recontrôle de ce dossier : un ID de proposition ne peut jamais devenir une
  commande exécutable.
- Les autorisations restent locales, explicites, à usage unique et liées au
  dossier exact. L'import et l'export n'en créent ni n'en transfèrent aucune.
- Les anciens dossiers sont préservés : archive avant remplacement sur PC et
  Android, et accès explicite à l'ancien dossier de test dans l'écran mobile.
  Les modifications déjà présentes dans la copie ont été sauvegardées avant
  cette étape (60 fichiers et leurs empreintes).

## Résultats vérifiés

| Vérification | Résultat |
| --- | --- |
| Python, suite complète | 248 tests, zéro échec |
| Android, suite unitaire | 276 tests, zéro échec, zéro erreur, 50 suites |
| Compilation Android | Application TEST et instrumentation construites hors ligne |
| Modifications / JavaScript | `git diff --check` et contrôle de syntaxe sans erreur |
| Navigateur, atelier vide réel | Aucune fixture chargée, aucun essai autorisé |
| Navigateur, atelier synthétique isolé | Import d'un rapport puis du dossier, critères visibles, lecture persistante, autorisation puis recontrôle distinct, résultat consultable, export, zéro erreur console observée |
| Pixel 10 / Jade TEST | Hypothèse et critère visibles, ancienne revue archivée, lecture persistante, proposition refusée comme autorisation, recontrôle local seul, non-réutilisation de la permission : PASS |
| Après arrêt et redémarrage de Jade TEST | Rapport, décisions et propositions retrouvés ; permission consommée toujours bloquée |
| Registre des preuves | Nombre d'entrées inchangé pendant les contrôles Pixel |
| Jade personnelle | Empreintes des APK installés, version 0.1.22 et date de mise à jour identiques avant/après |

Les observations utilisées pour valider le nouveau parcours sont **synthétiques**.
Elles démontrent le fonctionnement de la revue, pas une amélioration mesurée de
la mémoire, du routage ou de la qualité des réponses de Jade en production.

Seuls `com.jadegenesis.mobile.validation` et son paquet de tests ont été mis à
jour. Une sauvegarde des données privées de Jade TEST a précédé cette mise à
jour. Les données privées de Jade personnelle n'ont pas été ouvertes. Aucun
projet de jeu, commit, push, merge, déploiement VPS ou nouveau collecteur externe
n'a été utilisé.

## Utilisation locale

Depuis la copie isolée, démarrer le contrôleur avec Python 3.11+ :

```powershell
& 'C:\Users\alexa\AppData\Local\Programs\Python\Python311\python.exe' node-agent\local_research_review.py --port 8768
```

Ouvrir `http://127.0.0.1:8768`, importer un rapport de Jade, puis relire les
hypothèses et critères. Le dossier général est conservé dans
`capture_local/jade_learning_review.json`. Chaque remplacement conserve le
dossier précédent dans `capture_local/review_imports/`.

Sur Jade TEST : **Mémoire → Recherches et preuves**. Importer l'export local pour
retrouver le même rapport, puis examiner **Dossier**, **Expériences**,
**Autorisation** et **Journal**.

## Travail encore nécessaire

L'atelier prépare et contrôle la revue. Pour essayer une modification de Jade,
il reste à préparer un runner isolé, ses scénarios, le comportement de référence
et un plan d'exécution précis pouvant être autorisé séparément. Aucun essai de
modification n'est implicitement lancé par cette étape. La promotion d'une
amélioration et son déploiement restent des décisions distinctes.
