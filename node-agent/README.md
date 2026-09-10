# Jade Genesis — Distributed Node Runtime 0.1.7

The Node Runtime connects a PC or VPS to the same logical Jade Genesis identity. It remains dependency-free and keeps the compatible wire protocol `jade-genesis-node/0.0.6`.

The Node Runtime version is independent from the Android product version. Current Android product version on this branch is **0.1.20** (`versionCode 37`).

## Start

From the `node-agent` directory:

```powershell
py jade_node_agent.py
```

On Linux/VPS:

```bash
python3 jade_node_agent.py --node-kind VPS
```

The node ID, pairing token, port, node kind and runtime configuration are persisted in `~/.jade-genesis/node-agent.json` (or `%USERPROFILE%\.jade-genesis\node-agent.json` on Windows). The token is not printed by default; `--show-token` is an explicit local administrative action.

## Authenticated API

Endpoints include:

- `GET /health`
- `GET /runtime`
- `GET /diagnostics`
- `GET /tasks/<task_id>`
- `POST /task`
- `POST /tasks`

Allow-listed tasks include `genesis_probe`, `text_analysis`, `memory_consolidation`, `brain_chat`, `screen_analyze`, `vision_analyze` and, on VPS replicas, `shared_state_sync`.

The following are deliberately **not** exposed as arbitrary remote tasks in 0.1.20:

- Night Learning execution;
- Procedure Runtime execution;
- Skill Registry registration/activation;
- arbitrary shell/system commands.

## Cognitive Brain Profiles

Jade remains the persistent identity while local/external models are interchangeable cognitive resources. Requests can use bounded roles such as `FAST`, `GENERAL`, `REASONING`, `CODE` and `CRITIC`.

The runtime can score already-installed Ollama models against role, size and available GPU VRAM. Small models remain usable as bounded fallback resources but are not treated as Jade's identity.

Profile selection does not automatically download models, mutate model weights, grant privileges or expose shell execution.

## Shared Genesis State

On VPS nodes, the runtime maintains a durable operational-state replica in `~/.jade-genesis/genesis-state.json` plus a backup. Sync is identity-bound and idempotent by event ID.

The Pixel keeps its own local cache/outbox. A VPS replica is not the owner of Jade's identity, and temporary VPS loss does not erase local Android state.

## Night Cycle / Night Learning

A VPS can run the bounded Night Cycle supervisor. It reviews durable state, Runtime Evaluation summaries and bounded evolution signals.

Night Learning can produce research questions, hypotheses, experiment proposals and strategy hints. Those artifacts cannot automatically rewrite production code, mutate model weights or execute arbitrary commands.

## Adaptive Strategy Registry — 0.1.18 substrate

`adaptive_strategy_registry_v1` persists bounded strategy evidence and lifecycle metadata. It remains governance/adaptation infrastructure rather than proof of general learned behavior.

Automatic activation, automatic promotion, code rewrite and model-weight mutation remain disabled.

See `../docs/ADAPTIVE_STRATEGY_REGISTRY_0.1.18.md`.

## Verifiable Task Ledger — hardened in 0.1.20

`verifiable_task_ledger_v1` stores deterministic task cases in:

- `TRAIN`
- `VALIDATION`
- hidden `SEALED_TEST`

The hidden set is committed with a private random nonce. In 0.1.20, `SEALED_TEST` can no longer be queried through the interactive per-case evaluator.

A final sealed exam:

- requires the exact frozen SkillSpec commitment;
- evaluates the whole hidden set internally;
- exposes only aggregate `PASS/FAIL`;
- consumes the sealed dataset for the first candidate;
- rejects a different candidate after consumption;
- returns an idempotent stored result for the exact same candidate without rerunning hidden cases.

This blocks the obvious repeated-feedback oracle. It is not yet an OS-level secret enclave: the hidden data still physically exists in the verifier-owned local ledger file, so future synthesis must isolate generator access from that state.

## SkillSpec v1 — 0.1.20 contract

`SkillSpec` describes one closed declarative procedure with:

- skill identity/version;
- task family/domain;
- input/output contracts;
- `JADE_PROCEDURE_DSL_V1` AST body;
- provenance;
- evaluation policy;
- stable body/spec hashes.

Skill-to-Skill dependencies are deliberately disabled in 0.1.20 (`max_dependencies = 0`).

The contract itself does not grant execution permission. Runtime policy decides which provenance kinds may execute.

## Restricted Procedure Runtime — 0.1.20

`procedure_runtime.py` is the first real executable SkillSpec substrate.

Only `DEVELOPER`-authored SkillSpecs are executable in 0.1.20. `EXTERNAL_TEACHER` and future generated candidates remain blocked until the 0.1.21 synthesis/promotion boundary exists.

The interpreter is deterministic and exposes no primitive for:

- filesystem;
- network;
- shell/process;
- imports/eval/exec;
- environment variables;
- clock/time;
- randomness;
- arbitrary Python/native code.

Supported operations are intentionally small: bounded JSON input/literals, object/array construction, field/index lookup, string operations, arithmetic, comparison, boolean operators and `if`.

Execution is bounded by:

- AST validation limits;
- logical step budget;
- logical cost budget that grows with processed value size;
- JSON depth/collection/string/output-size limits;
- input/output contract checks;
- fail-closed arithmetic/type errors.

This is a restricted in-process interpreter, not an OS/container sandbox for hostile native code.

## Developer Skill Registry — 0.1.20

`skill_registry_v1` proves the first persistent causal execution path:

```text
DEVELOPER SkillSpec
-> register
-> explicit approved activation
-> exact task-family selection
-> restricted execution
-> deterministic result
-> persisted selection across restart
```

Properties:

- only developer-authored SkillSpecs can be registered/activated;
- activation requires explicit approval;
- no automatic promotion;
- no generated-skill registration/execution;
- no fuzzy recall yet;
- no LLM used for selection;
- no Skill-to-Skill dependencies;
- no network access by the registry.

This registry is separate from the Adaptive Strategy Registry because an executable skill is a capability payload, not merely tuning metadata.

## What 0.1.20 does NOT prove

0.1.20 proves that Jade's runtime can persist, select and execute an approved deterministic procedure safely within the DSL boundary.

It does **not** prove that Jade can autonomously acquire a new skill. The developer still authors/approves the executable SkillSpec. Autonomous gap detection, teacher proposal, candidate iteration, promotion and restart recall belong to 0.1.21+.

See `../docs/RESTRICTED_PROCEDURE_RUNTIME_0.1.20.md`.

## Resource and model telemetry

Runtime 0.1.7 preserves CPU/task/RAM telemetry, NVIDIA GPU/VRAM telemetry when available, Ollama model state and measured generation throughput. Existing vision and asynchronous-task capabilities remain available.

## Useful local commands

```powershell
py jade_node_agent.py --show-config
py jade_node_agent.py --probe-ollama
py jade_node_agent.py --show-token
```

Use `--reset-token` only when intentionally rotating the pairing token; paired Android clients will then need the new token.

## Safety / source-control note

Do not commit pairing tokens, private keys, signing material, hidden task-ledger state or local runtime-state files. CI logs and generated release artifacts belong in GitHub Actions/artifacts rather than in the canonical source tree.
