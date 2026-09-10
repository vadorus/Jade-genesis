# Jade Genesis — Architecture Overview

This document describes the current high-level architecture of Jade Genesis at Android product version **0.1.20**.

## Design intent

Jade is designed as one persistent logical identity distributed across several execution nodes. Android is the primary personal-device presence, while PC/VPS nodes provide additional compute, durable replication and supervised background processing.

Models are cognitive resources. They are not Jade's identity and are not assumed to be permanent.

The architecture separates four concepts that must not be conflated:

```text
memory != adaptation != learning != acquisition of a new skill
```

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
- Runtime Evaluation/outcome evidence;
- validation of synchronized learning/maintenance snapshots.

The phone keeps local identity/state when a PC/VPS node is unavailable. Rich conversational capability still depends on an available cognitive backend; the minimal local prototype/fallback must not be confused with the final brain architecture.

### PC / VPS Node Runtime

`node-agent/` is a dependency-free Python runtime. Current internal version is **0.1.7**, with compatible wire protocol `jade-genesis-node/0.0.6`.

Responsibilities include:

- execute authenticated allow-listed tasks;
- expose installed model and hardware capabilities;
- select bounded cognitive brain profiles;
- host a durable Shared Genesis State replica on VPS nodes;
- supervise Night Cycle processing on VPS;
- run Night Learning and persist adaptive strategy evidence;
- expose Verifiable Task Ledger / SkillSpec state;
- expose the restricted Procedure Runtime state;
- expose the developer-only Skill Registry state.

Procedure execution and Skill Registry mutation are deliberately not exposed as arbitrary remote tasks in 0.1.20. No generic remote shell task is exposed.

### Shared Genesis State

Shared Genesis State is a bounded, versioned synchronization substrate. It lets nodes exchange operational snapshots/events without making the VPS the owner of Jade's identity.

It supports bounded event history, latest-entity snapshots, identity binding, idempotent synchronization, backup/recovery and phone-side retry when the VPS is unavailable.

### Runtime Evaluation and Outcome Quality

Runtime Evaluation records bounded observations about selected cognitive profiles/models and outcomes. Explicit user feedback can contribute to personal operational quality evidence, but it must not be promoted into external factual truth automatically.

### Night Learning

The VPS-supervised Night Cycle can derive signals, research questions, hypotheses, experiment proposals and bounded improvement candidates / `STRATEGY_HINT` entries.

Night Learning does not automatically execute experiments, promote candidates, rewrite production code, run shell commands or mutate model weights.

### Adaptive Strategy Registry

The registry introduced in 0.1.18 persists bounded strategy evidence across restarts and provides lifecycle/governance metadata, evaluation metadata, explicit promotion gates and rollback.

It remains distinct from executable skills. Strategy state is not itself a general capability payload.

### Verifiable Task Ledger

The ledger stores deterministic machine-verifiable cases in:

- `TRAIN`;
- `VALIDATION`;
- hidden `SEALED_TEST`.

The hidden set is committed using a private random nonce.

0.1.20 hardens the anti-oracle boundary:

- sealed cases cannot be evaluated individually through `evaluate_case()`;
- a final frozen candidate must reference the exact sealed commitment;
- the verifier executes the complete hidden set internally;
- public feedback is aggregate `PASS/FAIL` only;
- the first final candidate consumes that sealed dataset;
- another candidate cannot retry on the same consumed hidden set;
- an exact-candidate replay is idempotent and returns the stored result.

The hidden dataset still physically exists in the ledger state file, so a future generator must be denied direct filesystem/state access to the verifier data.

### SkillSpec v1

`SkillSpec` describes one bounded declarative procedural capability.

It contains:

- stable skill identity/version;
- task family/domain;
- input/output contracts;
- `JADE_PROCEDURE_DSL_V1` AST body;
- provenance;
- evaluation policy;
- stable body/spec hashes.

In 0.1.20 Skill-to-Skill dependencies are disabled (`max_dependencies = 0`). The SkillSpec contract does not itself grant permission to execute.

### Restricted Procedure Runtime

0.1.20 adds `procedure_runtime.py`, the first executable SkillSpec substrate.

Only explicitly developer-authored SkillSpecs are executable in this milestone. Generated/external-teacher SkillSpecs remain blocked.

The interpreter supports a small deterministic operation set over bounded JSON values: input/literals, object/array creation, field/index access, string transforms, arithmetic, comparisons, booleans and conditionals.

It exposes no filesystem, network, shell/process, environment, import, eval/exec, clock, randomness or arbitrary Python capability. There are no loops or Skill-to-Skill calls.

Execution is bounded by AST/value limits plus logical step and cost budgets. The interpreter is in-process; it is not an OS/container sandbox for native hostile code.

### Developer Skill Registry

0.1.20 adds a persistent identity-bound Skill Registry separate from the Adaptive Strategy Registry.

It proves:

```text
DEVELOPER SkillSpec
 -> register
 -> explicit approved activation
 -> exact task-family selection
 -> restricted execution
 -> deterministic result
 -> persisted state/reload
```

It does not perform fuzzy retrieval, LLM selection, automatic promotion or generated-skill activation.

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
                                              +---- cognitive model resources
                                              |
                                              +---- Shared Genesis State
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
                    Verifiable Task Ledger                             SkillSpec
                              |                                             |
                              +--------------------------+------------------+
                                                         |
                                                         v
                                             Restricted Procedure Runtime
                                                         |
                                                         v
                                              Developer Skill Registry
                                                         |
                                                         v
                                            deterministic task result
```

The 0.1.21 target will add a controlled path from capability gaps and visible examples to an externally proposed candidate SkillSpec, while preserving the verifier boundary.

## Trust boundaries

### Node transport

Node requests are authenticated. Pairing tokens are runtime secrets and are not intended for source control.

### Model boundary

A local or external LLM can reason or suggest artifacts, but its output is untrusted until validated by Jade's bounded policies/evaluators.

### Procedure boundary

The DSL is capability-minimal. A procedure cannot directly invoke host filesystem/network/process/import/environment/clock/randomness facilities.

### Skill activation boundary

Only developer-authored skills may enter the executable Skill Registry in 0.1.20, and activation requires explicit approval.

### SEALED_TEST boundary

The generator/teacher must never receive hidden cases or per-case final-test feedback. In 0.1.20 the API prevents iterative per-case sealed evaluation, but stronger process/filesystem separation remains future work.

### Signing boundary

Stable Android signing secrets are injected only in the explicit stable-release path. Ordinary CI must not receive them.

## Failure and recovery model

- Phone temporarily offline from VPS: local state remains available; bounded outbox retries later.
- VPS unavailable: Android identity is not lost and does not transfer ownership.
- Registry/state corruption: persistent Node-side stores use backup recovery where implemented.
- Unsafe learning snapshot: dangerous flags are rejected fail-closed by current integration.
- Procedure invalid/over budget: deterministic execution fails closed.
- No active exact-family skill: Skill Registry reports no matching active skill instead of inventing one.
- Failed final SEALED_TEST candidate: the hidden dataset remains consumed; a new hidden dataset is required for a new final candidate.

## Source of truth

The checked-in Git source and exact commit tested by CI are authoritative. Historical ZIP snapshots and copied CI logs are not development inputs.

Release-quality verification should record at least:

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

Current milestone: deterministic single-Skill interpreter, developer-only persistent registry, exact-family selection/reuse and SEALED_TEST final-exam hardening.

### 0.1.21 — First autonomous skill-acquisition proof

Target experiment:

```text
capability gap
 -> LearningGoal
 -> external-model proposed SkillSpec candidate
 -> restricted execution
 -> TRAIN/VALIDATION iteration
 -> freeze spec_sha256
 -> one-shot sealed evaluation
 -> retain or reject
 -> full restart
 -> recall and reuse on unseen cases
 -> no teaching-LLM call for final execution
```

Success requires a capability whose executable body was not manually written by the developer, survives restart and is reused correctly on later matching tasks.

### 0.1.22+

Improve multi-skill retrieval, confidence/abstention, regression/rollback and pruning before introducing controlled Skill-to-Skill composition and broader generalisation experiments.
