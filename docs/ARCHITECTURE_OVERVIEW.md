# Jade Genesis — Architecture Overview

This document describes the current high-level architecture of Jade Genesis at Android product version 0.1.19.

## Design intent

Jade is designed as one persistent logical identity distributed across several execution nodes. Android is the primary personal-device presence, while PC/VPS nodes provide additional compute, durable replication and supervised background processing.

Models are cognitive resources. They are not Jade's identity and are not assumed to be permanent.

## Components

### Android / Pixel

Responsibilities include:

- persistent Jade identity;
- local memory and user/device context;
- device profiling and self-model state;
- UI and user interaction;
- local tool access allowed by the Android app;
- node discovery/pairing and authenticated requests;
- local Shared Genesis State cache/outbox;
- validation of synchronized learning/maintenance snapshots.

The phone must continue to function when a PC/VPS node is unavailable. Unsynchronized bounded operational state is retained locally and retried later.

### PC / VPS Node Runtime

`node-agent/` is a dependency-free Python runtime. It provides authenticated task endpoints and resource/model telemetry.

Typical responsibilities:

- execute allow-listed tasks;
- expose installed model and hardware capabilities;
- select bounded cognitive brain profiles;
- host a durable Shared Genesis State replica on VPS nodes;
- supervise Night Cycle processing on VPS;
- run Night Learning and persist adaptive strategy evidence;
- expose Verifiable Task Ledger / SkillSpec capability telemetry.

It exposes no arbitrary remote shell task.

### Shared Genesis State

Shared Genesis State is a bounded, versioned synchronization substrate. It lets nodes exchange operational snapshots/events without making the VPS the owner of Jade's identity.

It supports:

- bounded event history;
- latest-entity snapshots;
- identity binding;
- idempotent synchronization;
- local backup/recovery;
- phone-side retry when the VPS is unavailable.

### Runtime Evaluation and Outcome Quality

Runtime Evaluation records bounded observations about selected cognitive profiles/models and outcomes. Explicit user feedback can contribute to personal operational quality evidence, but it must not be promoted into external factual truth.

### Night Learning

The VPS-supervised Night Cycle consumes bounded synchronized state and can derive:

- signals;
- research questions;
- hypotheses;
- experiment proposals;
- improvement candidates / `STRATEGY_HINT` entries.

Night Learning does not automatically execute experiments, promote candidates, rewrite production code, run shell commands or mutate model weights.

### Adaptive Strategy Registry

The registry introduced in 0.1.18 persists bounded strategy evidence across restarts. It provides lifecycle/governance around strategy candidates, sandbox-evaluation metadata, explicit promotion and rollback.

At 0.1.19 this is still governance infrastructure: ACTIVE strategy consumption is not yet the general executable skill mechanism.

### Verifiable Task Ledger

0.1.19 introduces a ledger for deterministic machine-verifiable tasks with separated partitions:

- `TRAIN`;
- `VALIDATION`;
- hidden `SEALED_TEST`.

A sealed dataset is immutable. The sealed commitment uses a private nonce so published digests do not directly reveal low-entropy expected answers. Evaluation returns verdicts without exposing expected hidden outputs.

### SkillSpec v1

`SkillSpec` defines the future payload of a Jade-owned procedural skill.

It includes:

- stable skill identity/version;
- input/output contracts;
- declarative `JADE_PROCEDURE_DSL_V1` AST body;
- provenance and dependencies;
- evaluation policy;
- stable body/spec hashes.

0.1.19 deliberately has no interpreter. Generated SkillSpecs cannot execute.

## Data and decision flow

```text
User / environment
      |
      v
Android Jade identity
      |
      +---- local memory / tools / device state
      |
      +---- authenticated task request ----> PC/VPS Node Runtime
                                              |
                                              +---- model/hardware resources
                                              |
                                              +---- Shared Genesis State replica
                                                         |
                                                         v
                                                  Runtime Evaluation
                                                         |
                                                         v
                                                   Night Learning
                                                         |
                                                         v
                                               Adaptive Strategy Registry
                                                         |
                              +--------------------------+------------------+
                              |                                             |
                              v                                             v
                    Verifiable Task Ledger                         SkillSpec contract
                              |                                             |
                              +--------------------------+------------------+
                                                         |
                                                         v
                                           future restricted interpreter
                                                         |
                                                         v
                                            machine-verifiable verdict
```

## Trust boundaries

### Node transport

Node requests are authenticated. Android requires an authorized route to the node. Pairing tokens are runtime secrets and are not intended for source control.

### Model boundary

A local or external LLM can suggest, reason or generate candidate artifacts, but its output is untrusted until validated by Jade's bounded policies/evaluators.

### Learning boundary

Current Night Learning output is advisory/persistent evidence. It cannot promote itself into production behavior.

### Future skill boundary

Future generated skills must execute only through the restricted Jade procedure DSL interpreter. Arbitrary Python, shell/process creation, network, filesystem, dynamic import, `eval`/`exec` and arbitrary-code capabilities are outside the SkillSpec v1 contract.

### Signing boundary

Stable Android signing secrets are injected only in the explicit stable-release workflow. Ordinary CI must not receive them.

## Failure and recovery model

- Phone temporarily offline from VPS: local state remains available; bounded outbox retries later.
- VPS unavailable: Android identity is not lost and does not transfer ownership to another node.
- Registry/state corruption: persistent Node-side stores use backup recovery where implemented.
- Unsafe learning snapshot: Android/Node integration rejects dangerous flags fail-closed.
- Future skill failure: the intended architecture requires deterministic evaluation, fallback and rollback rather than self-declared success.

## Source of truth

The checked-in Git source and exact commit tested by CI are authoritative. Historical ZIP snapshots and copied CI logs are not development inputs.

For release-quality verification, record at least:

- exact source SHA;
- Android `versionName` / `versionCode`;
- package/application ID;
- real test/build result;
- reproducible evaluation result;
- debuggable state;
- signing identity for stable builds;
- artifact SHA-256;
- proof that tracked sources were not rewritten by the build.

## Near-term evolution

### 0.1.20 — Restricted Procedure Runtime

Implement a deterministic interpreter for the already allow-listed `JADE_PROCEDURE_DSL_V1` operations. Prove that a manually authored SkillSpec can be selected, executed and evaluated without arbitrary-code capabilities.

### 0.1.21 — First autonomous skill-acquisition proof

The target experiment is:

```text
capability gap
 -> LearningGoal
 -> model-generated SkillSpec candidate
 -> restricted execution
 -> TRAIN/VALIDATION feedback
 -> sealed evaluation
 -> retain or reject
 -> restart Jade
 -> recall and reuse on unseen cases
```

Success requires a capability whose body was not manually written by the developer, survives restart and can execute locally on later instances without another model call for execution.
