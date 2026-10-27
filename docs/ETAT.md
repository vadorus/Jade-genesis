# Jade Genesis — état et décisions

> Fichier court et stable : le **pourquoi**. Ce qui est en cours est dans le tableau de bord #93.
> Dernière mise à jour : 27/10/2026, d'après `main` au commit `2920ac9`.

## Mission
Accompagner Alexandre dans la durée, en ne tenant pour vrai que ce qui a été vérifié.

## Principes (ne pas reproposer l'inverse)
- **Le LLM n'est pas Jade.** Les cerveaux sont interchangeables (téléphone, PC, VPS, cloud), sans dépendance obligatoire à une API payante. Les modèles tout faits ne servent qu'au départ : Jade doit apprendre et évoluer par elle-même.
- **Mémoire :** un résultat de recherche ou une réponse unique de modèle ne devient jamais automatiquement une connaissance stable.
- **Repo-first :** le code vit en vrais fichiers Git ; les workflows ne génèrent ni ne patchent les sources ; rollback par commit/tag ; `versionCode` toujours croissant.
- **Tout le travail reste sur GitHub**, pas seulement sur les PC : quand ils sont éteints, Alexandre ne doit pas être bloqué.
- On distingue toujours orchestration, Skills, code et poids. On améliore d'abord l'orchestration, pas les poids.

## Infrastructure
- Nœuds reliés par Tailscale : Pixel 10, PC Home (Ollama, éteint la nuit), VPS Hetzner (24 h/24, runtime sur le port 8766).
- App Android construite par GitHub Actions, signée avec une clé de développement stable.

## Où on en est
- Boucle d'apprentissage borné démontrée (sept. 2026) : Skill apprise sur TRAIN/VALIDATION, vérifiée sur SEALED_TEST, réutilisée après redémarrage sans appel au modèle.
- Mémoire des corrections : V1a (retenir) et V1b (mesurer) fusionnées dans `main` le 24/10 (PR #100 et #101, issue #85).
- Packaging du nœud Windows restauré (PR #103) et admission du cerveau résident sur GPU corrigée (PR #105), fusionnés les 24 et 25/10.

## Décisions et raisons
- **Mémoire des corrections avant l'apprentissage de procédures** (oct. 2026) : quand Alexandre corrige Jade, elle doit le retenir et ne plus refaire l'erreur ; c'est la base de la confiance.
- **Direction future** (25/10) : Jade entraîne ses propres petits réseaux sur ses données (PC/VPS), vérifiés puis exécutés sur le TPU du Pixel 10. But : parler, apprendre et évoluer sur le Pixel seul.
- **Orchestration semi-automatique** (23/10) : Alexandre transmet entre les IA. Le tout automatique est écarté pour l'instant : il consommerait le quota Codex au lieu du mode discussion gratuit de ChatGPT, et Gemini publie encore avec le compte d'Alexandre.
