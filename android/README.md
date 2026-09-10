# Jade Genesis Android — current 0.1.20.1

Native Android / Pixel application for Jade Genesis.

Current package/version:

- application ID: `com.jadegenesis.mobile`
- versionName: `0.1.20.1`
- versionCode: `38`
- minSdk: `31`
- compileSdk / targetSdk: `37`

## Role in the system

The Android app is Jade's primary personal-device presence. It owns the local persistent identity and can continue operating when a PC/VPS node is unavailable, although richer cognitive replies depend on an available brain backend.

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
- visibility into current adaptive/verifiable-learning substrate state.

## Conversation status

The Android UI is already a persistent Jade interface, but 0.1.20.1 should not be presented as a finished standalone conversational assistant. When no suitable PC/VPS/model backend is available, the app may expose only the minimal local prototype/fallback behavior.

The distributed `brain_chat` path and cognitive profiles exist in the Node Runtime. A later Android integration milestone still needs to make everyday routing, backend availability, chosen brain and fallback behavior clearer to the user.

## Cognitive plumbing hardening — 0.1.20.1

This maintenance milestone fixes several Android producer-to-consumer paths found during a code audit:

- `MemoryStore.latestForContext()` reserves bounded space for USER facts and `JADE_CONSOLIDATION_*` knowledge before recent memory fills the rest;
- `LocalPCBrain` no longer sorts consolidated knowledge behind volatile memories before its final truncation;
- exact textual duplicates from a successfully consolidated batch can be marked superseded, while USER facts and heuristic contradictions remain protected;
- stale transient `VISION_*` observations have a dedicated compiled retention ceiling compatible with the normal `0.68` visual confidence;
- the Shared Genesis State phone cache semantically coalesces repeated operational snapshots by `(originNode, kind, entityId)`, preserving rarer durable learning/night-cycle events from snapshot churn;
- Evolution evidence uses an independent 12→24 sample confidence curve and an unsaturated `rawScore`, so its confidence and score-improvement gates remain meaningful if the engine is wired later.

The Evolution Engine remains intentionally unconnected to autonomous proposal/testing/promotion in this version.

See [`../docs/COGNITIVE_PLUMBING_HARDENING_0.1.20.1.md`](../docs/COGNITIVE_PLUMBING_HARDENING_0.1.20.1.md).

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
6. Connect the Android device with USB debugging enabled for a debug run.
7. Run the `app` configuration.

## Command-line build

Canonical GitHub Actions currently installs Gradle 9.6.0 explicitly and runs:

```bash
gradle --no-daemon --stacktrace --console=plain -p android \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleRelease
```

`gradle/wrapper/gradle-wrapper.properties` pins the Gradle 9.6.0 binary distribution and its official SHA-256 checksum. The repository still does not bundle `gradle-wrapper.jar`; until that is corrected, local command-line builds should use Android Studio or an installed Gradle 9.6.0 matching CI.

## Signing model

Debug builds use the normal Android debug signing path.

Ordinary CI release builds are intentionally unsigned. Stable signing credentials are injected only in the explicit stable-release workflow path and must never be committed or printed.

## Distributed operation

Android communicates with the Node Runtime through authenticated node traffic. A paired PC/VPS can provide additional compute/model resources and, on VPS, a durable Shared Genesis State replica plus supervised Night Cycle processing.

The VPS is not the owner of Jade's identity. If it is unavailable, the phone retains local state and retries bounded synchronization later.

## Learning status

Android participates in the evidence/governance side of Jade's learning architecture. The deterministic Procedure Runtime and developer-only Skill Registry live on the Python Node Runtime side.

Current foundations include:

- restricted `JADE_PROCEDURE_DSL_V1` execution;
- developer-only Skill Registry;
- exact task-family selection;
- persistent reuse of explicitly activated developer Skills;
- single-use aggregate `SEALED_TEST` final exams;
- no per-case sealed verdict oracle.

Important limits:

- Android does not autonomously synthesize Skills;
- generated/external-teacher Skills remain non-executable;
- Skill-to-Skill dependencies are disabled;
- there is no automatic Skill promotion into production behavior;
- the Android Evolution Engine is still dormant from autonomous use;
- 0.1.20.1 is not proof of autonomous skill acquisition.

The planned 0.1.21 milestone will attempt the first falsifiable synthesis/restart/reuse proof.

## Related documentation

- [`../README.md`](../README.md)
- [`../docs/ARCHITECTURE_OVERVIEW.md`](../docs/ARCHITECTURE_OVERVIEW.md)
- [`../docs/ADAPTIVE_STRATEGY_REGISTRY_0.1.18.md`](../docs/ADAPTIVE_STRATEGY_REGISTRY_0.1.18.md)
- [`../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md`](../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md)
- [`../docs/RESTRICTED_PROCEDURE_RUNTIME_0.1.20.md`](../docs/RESTRICTED_PROCEDURE_RUNTIME_0.1.20.md)
- [`../docs/COGNITIVE_PLUMBING_HARDENING_0.1.20.1.md`](../docs/COGNITIVE_PLUMBING_HARDENING_0.1.20.1.md)

When documentation and source disagree, the exact repository commit and its CI result are authoritative.
