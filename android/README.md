# Jade Genesis Android — current 0.1.19

Native Android / Pixel application for Jade Genesis.

Current package/version:

- application ID: `com.jadegenesis.mobile`
- versionName: `0.1.19`
- versionCode: `36`
- minSdk: `31`
- compileSdk / targetSdk: `37`

## Role in the system

The Android app is Jade's primary personal-device presence. It owns the local persistent identity and can continue operating even when a PC/VPS node is unavailable.

Current responsibilities include:

- persistent Jade identity;
- local structured memory;
- device profiling and self-model state;
- Jetpack Compose UI;
- tool registry and Android-side capabilities;
- authenticated communication with paired Node Runtime instances;
- local Shared Genesis State cache/outbox with retry;
- bounded Runtime Evaluation / outcome data;
- validation of synchronized Night Learning / maintenance snapshots;
- exposure of current verifiable-learning substrate state.

## Stack

- Kotlin 2.3.21
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Java/JDK 17
- Jetpack Compose BOM 2026.08.00
- Room 3.0.2
- DataStore 1.2.1

## Open in Android Studio

1. Clone the repository.
2. Open the `android/` directory in a recent Android Studio.
3. Use JDK 17.
4. Install Android API 37 and Build Tools 36.0.0.
5. Let Gradle synchronize dependencies.
6. Connect the Android device with USB debugging enabled if you want to run a debug build.
7. Run the `app` configuration.

## Command-line build

The canonical GitHub Actions build installs Gradle 9.6.0 explicitly and runs:

```bash
gradle --no-daemon --stacktrace --console=plain -p android \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleRelease
```

The lightweight `gradlew` / `gradlew.bat` scripts in this prototype do not currently bundle the Gradle wrapper JAR. For local command-line work, install Gradle 9.6.0 or regenerate a full wrapper intentionally.

## Signing model

Debug builds use the normal Android debug signing path.

Ordinary CI release builds are intentionally unsigned. Stable signing credentials are injected only in the explicit `stable-release` workflow path and must never be committed or printed.

## Distributed operation

Android communicates with the Node Runtime through authenticated node traffic. A paired PC/VPS can provide additional compute/model resources and, on VPS, a durable Shared Genesis State replica plus supervised Night Cycle processing.

The VPS is not the owner of Jade's identity. If it is unavailable, the phone retains local state and retries bounded synchronization later.

## Learning status

As of 0.1.19, Android participates in the evidence/governance side of the learning architecture but does **not** execute model-generated skills.

Implemented foundations include:

- Runtime Evaluation;
- Outcome Quality feedback;
- Night Learning snapshot validation;
- Adaptive Strategy Registry visibility;
- Verifiable Task Ledger capability visibility;
- SkillSpec v1 capability visibility.

The planned 0.1.20 restricted procedure runtime will be the first execution layer for declarative SkillSpecs. The planned 0.1.21 synthesis loop will attempt the first measurable autonomous acquisition of a new deterministic skill.

## Related documentation

- [`../README.md`](../README.md)
- [`../docs/ARCHITECTURE_OVERVIEW.md`](../docs/ARCHITECTURE_OVERVIEW.md)
- [`../docs/ADAPTIVE_STRATEGY_REGISTRY_0.1.18.md`](../docs/ADAPTIVE_STRATEGY_REGISTRY_0.1.18.md)
- [`../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md`](../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md)

When documentation and source disagree, the exact repository commit and its CI result are authoritative.
