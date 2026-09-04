# Jade Genesis Android 0.0.1 — Pixel-first Core

Premier noyau **Android natif** de Jade Genesis.

## Objectif de cette version

Cette V0.0.1 tourne directement sur le téléphone et ne dépend ni du PC ni du VPS.

Elle contient déjà :

- `IdentityManager` : identité Jade persistante via DataStore ;
- `DeviceProfiler` : informations réelles sur le téléphone ;
- `SelfModel` : représentation réelle des capacités et limites de Jade ;
- `MemoryStore` : mémoire locale structurée avec Room 3 ;
- `BrainBackend` : interface de cerveau interchangeable ;
- `PrototypeBrain` : cerveau déterministe minimal pour tester l'architecture ;
- `ToolRegistry` : registre d'outils ;
- `inspect_device` : premier outil réel ;
- interface Jetpack Compose.

## Stack

- Kotlin 2.3.21
- Android Gradle Plugin 9.4.0
- Gradle 9.6
- compileSdk / targetSdk 37
- Jetpack Compose BOM 2026.08.00
- Room 3.0.2
- DataStore 1.2.1

## Ouvrir dans Android Studio

1. Extraire le projet.
2. Ouvrir le dossier `jade-genesis-android-0.0.1` dans Android Studio.
3. Laisser Gradle synchroniser les dépendances.
4. Brancher le Pixel avec le débogage USB activé.
5. Lancer l'application `app`.

Android Studio récent avec JDK 17+ recommandé.

## Premier test

Au lancement Jade doit afficher :

- son identité persistante ;
- le modèle du téléphone ;
- le SoC ;
- l'architecture ;
- la RAM ;
- le stockage ;
- la batterie ;
- l'état thermique ;
- ses capacités connues.

Essaie ensuite :

- `Qui es-tu ?`
- `Inspecte ton téléphone`
- `Retiens que le test Genesis vaut 42`
- puis `Afficher la mémoire`

## Architecture

```text
Android / Pixel
│
├── core/
│   └── JadeCore
├── identity/
│   └── IdentityManager
├── device/
│   └── DeviceProfiler
├── selfmodel/
│   └── SelfModelBuilder
├── memory/
│   ├── Room 3
│   └── MemoryStore
├── brain/
│   ├── BrainBackend
│   └── PrototypeBrain
├── tools/
│   └── ToolRegistry
└── ui/
    └── Compose
```

## Étape suivante : 0.0.2

- brancher un vrai modèle local/cloud derrière `BrainBackend` ;
- retrieval de mémoire pertinente ;
- journal d'expérience ;
- boucle `Think -> Tool -> Observation -> Think` ;
- préparer le `NodeManager` pour détecter PC/VPS.

Le but reste : **Jade existe d'abord sur le Pixel**, puis elle étendra son exécution vers PC et VPS.
