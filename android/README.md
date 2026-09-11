# Jade Genesis Android — current 0.1.21

Native Android / Pixel application for Jade Genesis.

Current package/version:

- application ID: `com.jadegenesis.mobile`
- versionName: `0.1.21`
- versionCode: `39`
- minSdk: `31`
- compileSdk / targetSdk: `37`

## Role in the system

The Android app is Jade's primary personal-device presence. It owns a local persistent copy of Jade's identity and state and remains usable when richer PC/VPS resources are unavailable.

Current responsibilities include persistent identity, local structured memory, device profiling/self-model state, Jetpack Compose UI, tool registry, authenticated Node Runtime communication, Shared Genesis State cache/outbox, Runtime Evaluation/outcome data, and visibility into distributed learning/night-cycle state.

## Conversation status

Android can route `brain_chat` requests to compatible PC/VPS nodes and falls back to the minimal local prototype when the richer backend is unavailable. 0.1.21 does not claim that Jade's everyday conversational system is finished; backend availability and routing are still important operational dependencies.

## 0.1.21 first causal-learning command

For the first falsifiable procedural-learning experiment, Android recognizes one explicit command only:

```text
/normalize <text>
```

This command is mapped to the deterministic family `normalize_label_v1` and routed specifically to a node that advertises both:

```text
learning_workshop_v1
verified_skill_pre_ollama_dispatch_v1
```

In the current architecture that means the VPS workshop. Normal conversation keeps the ordinary adaptive PC/VPS routing policy.

Only this explicit command is tagged:

```text
traffic_source = REAL_USER
learning_observation_allowed = true
```

Ordinary conversation is therefore not silently turned into the first Skill-learning dataset.

Before an active Skill exists, the real input can be recorded as one verifier-owned case and the request still falls through to the normal cognitive backend. Every fifth unique real input completes and immediately seals one dataset using the partition plan fixed before teaching:

```text
TRAIN, TRAIN, VALIDATION, SEALED_TEST, SEALED_TEST
```

When a dataset is sealed, the Pixel response displays only its dataset ID and `sealed_set_sha256`. It never displays hidden SEALED_TEST inputs, answers or the private seal nonce. That hash is intended to be published as external evidence before any teacher call.

After a verified learned Skill is active, the same `/normalize` path can be answered by the retained deterministic procedure before the Node calls Ollama. Android renders the normalized text directly instead of exposing the internal Skill result JSON.

The formal 0.1.21 proof still requires a real VPS process restart followed by a genuinely new Pixel input and a measured Ollama-call delta of zero. See [`../docs/SKILL_SYNTHESIS_LOOP_0.1.21.md`](../docs/SKILL_SYNTHESIS_LOOP_0.1.21.md).

## Cognitive plumbing hardening inherited from 0.1.20.1

0.1.20.1 fixed several Android producer-to-consumer paths before Skill synthesis was connected:

- bounded USER and `JADE_CONSOLIDATION_*` memory slots reach the brain;
- exact textual duplicates can be superseded after successful consolidation while USER facts remain protected;
- stale transient `VISION_*` observations use a dedicated retention ceiling;
- Shared Genesis State operational snapshots are semantically coalesced so rarer Night Learning events survive cache churn;
- Evolution evidence uses an independent confidence curve and unsaturated comparison score.

The Android Evolution Engine remains intentionally separate from the 0.1.21 Skill-learning proof.

## Stack

- Kotlin 2.3.21
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Java/JDK 17
- Jetpack Compose BOM 2026.08.00
- Room 3.0.2
- DataStore 1.2.1

## Build

Canonical GitHub Actions installs Gradle 9.6.0 explicitly and runs real unit tests plus debug and release assembly:

```bash
gradle --no-daemon --stacktrace --console=plain -p android \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleRelease
```

`gradle/wrapper/gradle-wrapper.properties` pins the Gradle 9.6.0 binary distribution and its official SHA-256 checksum. The repository still does not bundle `gradle-wrapper.jar`; use Android Studio or an installed Gradle 9.6.0 matching CI until a complete verified wrapper is committed.

## Signing model

Debug builds use normal Android debug signing. Ordinary CI release builds are intentionally unsigned. Stable signing credentials are injected only in the explicit signed-release path and must never be committed or printed.

## Distributed operation

Android communicates with authenticated Node Runtime instances. The VPS hosts the canonical 0.1.21 workshop/archive for the first learning trial; the phone remains the personal interface and keeps local Jade identity/state. The VPS is not Jade's identity owner.

The temporary 0.1.21 Skill lookup occurs inside the Node `brain_chat` path before Ollama but after Android has selected the node and acquired a Resource Lease. Moving Skill dispatch upward into the task router is intentional post-proof debt, not part of the first experiment.

## Important learning limits

0.1.21 is a bounded procedural-learning experiment. It does not grant learned Skills shell, filesystem, network, process, arbitrary Python, randomness or Skill-to-Skill dependencies. Teacher models propose a restricted DSL body; the verifier-owned Ledger decides the hidden final exam; only an exact verified artifact may receive a production route.

Repository tests prove the mechanism, including sealed-data isolation, three-dataset reserve, teacher restrictions and zero-Ollama Skill short-circuiting. They do **not** replace the required live hard-restart proof on real Pixel/VPS traffic.

## Related documentation

- [`../README.md`](../README.md)
- [`../docs/ARCHITECTURE_OVERVIEW.md`](../docs/ARCHITECTURE_OVERVIEW.md)
- [`../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md`](../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md)
- [`../docs/RESTRICTED_PROCEDURE_RUNTIME_0.1.20.md`](../docs/RESTRICTED_PROCEDURE_RUNTIME_0.1.20.md)
- [`../docs/COGNITIVE_PLUMBING_HARDENING_0.1.20.1.md`](../docs/COGNITIVE_PLUMBING_HARDENING_0.1.20.1.md)
- [`../docs/SKILL_SYNTHESIS_LOOP_0.1.21.md`](../docs/SKILL_SYNTHESIS_LOOP_0.1.21.md)

When documentation and source disagree, the exact repository commit and its verified CI result are authoritative.
