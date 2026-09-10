# Jade Genesis — Distributed Node Runtime 0.1.6

Node Runtime connects a PC or VPS to the same logical Jade Genesis identity. The runtime stays dependency-free and keeps the compatible wire protocol `jade-genesis-node/0.0.6`.

The Node Runtime internal version is intentionally independent from the Android product version. Current Android product version is 0.1.19.

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

All Jade requests use the existing authenticated Node Runtime transport. Android requires an authorized route for authenticated node traffic.

Endpoints include:

- `GET /health`
- `GET /runtime`
- `GET /diagnostics`
- `GET /tasks/<task_id>`
- `POST /task`
- `POST /tasks`

Allow-listed tasks include `genesis_probe`, `text_analysis`, `memory_consolidation`, `brain_chat`, `screen_analyze`, `vision_analyze` and, on VPS replicas, `shared_state_sync`. The Night Learning Lab is intentionally **not** a remotely invokable task. No arbitrary remote shell/system command is exposed.

## Cognitive Brain Profiles v1

Runtime 0.1.6 introduces role-aware brains. Jade remains the persistent identity and the model remains an interchangeable cognitive resource. A request is assigned one bounded profile before local inference:

- `FAST` — short, low-cost replies;
- `GENERAL` — normal conversation and balanced reasoning;
- `REASONING` — architecture, diagnosis, planning and difficult revisions;
- `CODE` — programming and Tool Lab work;
- `CRITIC` — verification passes with low temperature.

The Android Cognitive Core requests the profile. The Node Runtime then scores the Ollama models that are already installed against the requested role, model size/parameter count and currently available GPU VRAM. Small 3B/4B-class models can still serve `FAST` or act as a degraded fallback, but they are deliberately penalized for `GENERAL`, `REASONING`, `CODE` and `CRITIC` when a stronger installed model is available. Jade therefore does not treat a tiny model as its central identity or mandatory brain.

Optional local overrides can be placed in `node-agent.json` with `brain_model_fast`, `brain_model_general`, `brain_model_reasoning`, `brain_model_code` and `brain_model_critic`. The legacy `ollama_model` setting remains a GENERAL-profile override for compatibility.

The runtime advertises `cognitive_brain_profiles_v1` plus a `brain_profiles` snapshot in `/health` and `/runtime`. Profile selection never downloads a model automatically, mutates model weights, grants privileges or exposes shell execution.

## Shared Genesis State v1

Runtime 0.1.6 keeps the durable operational-state replica on nodes configured as `VPS`. It advertises Shared Genesis State capabilities through `/health` and `/runtime`.

The replica stores a bounded, versioned event stream and a compact latest-entity snapshot in `~/.jade-genesis/genesis-state.json`, with a backup copy. Sync is idempotent by event ID and bound to one Jade identity. A VPS replica does **not** become the sole owner of Jade's identity.

The Pixel side keeps its own local cache and outbox. If the VPS is temporarily unavailable, unsynced operational snapshots remain on the phone and are retried later.

## VPS-supervised Night Cycle + Night Learning

On a node configured as `VPS`, Runtime 0.1.6 starts a bounded local supervisor. It waits for the protected inactivity/night-cycle conditions before reviewing the durable Shared Genesis State replica, recent Runtime Evaluation summaries and bounded evolution signals.

Night Learning turns measured runtime evidence into a bounded review pipeline: targeted research questions, public evidence when available, falsifiable hypotheses, experiment proposals and improvement candidates.

Experiments are plans only. Improvement artifacts cannot activate themselves, rewrite production code, change compiled SafetyPolicy, mutate Pixel memory, mutate model weights or execute shell commands.

The VPS writes bounded learning/maintenance/night-cycle snapshots back into Shared Genesis State so Android can receive them through normal synchronization. Dangerous learning flags are rejected fail-closed by the current integration.

## Adaptive Strategy Registry — 0.1.18 substrate

The Node Runtime now exposes `adaptive_strategy_registry_v1`.

The registry persists identity-bound `STRATEGY_HINT` evidence across restarts with lifecycle metadata, provenance, sandbox-evaluation metadata, explicit promotion gates and rollback support.

Important limitation: this is governance/persistence infrastructure. It does not mean Jade already owns a general executable learned skill. Automatic activation, automatic promotion, production-code rewrite and model-weight mutation remain disabled.

See `../docs/ADAPTIVE_STRATEGY_REGISTRY_0.1.18.md`.

## Verifiable Task Ledger + SkillSpec — 0.1.19 substrate

The Node Runtime now also exposes:

- `verifiable_task_ledger_v1`
- `skill_spec_v1`

The Verifiable Task Ledger stores bounded deterministic task cases in `TRAIN`, `VALIDATION` and hidden `SEALED_TEST` partitions. Sealed datasets are immutable and use a salted commitment whose private nonce is not part of learning-visible data.

`SkillSpec v1` defines a Jade-owned declarative skill payload with:

- stable identity/version;
- input/output contracts;
- a `JADE_PROCEDURE_DSL_V1` AST body;
- dependencies and provenance;
- evaluation policy;
- stable body/spec hashes.

0.1.19 deliberately has no interpreter. Runtime telemetry reports the non-executable boundary and generated SkillSpecs cannot run yet.

See `../docs/VERIFIABLE_TASK_LEDGER_SKILLSPEC_0.1.19.md`.

## Resource and model telemetry

Runtime 0.1.6 preserves resource intelligence for CPU load, task count, RAM, NVIDIA GPU/VRAM telemetry when available, Ollama model state and measured generation throughput. Existing vision and asynchronous-task capabilities remain available.

## Useful local commands

```powershell
py jade_node_agent.py --show-config
py jade_node_agent.py --probe-ollama
py jade_node_agent.py --show-token
```

Use `--reset-token` only when intentionally rotating the pairing token; existing paired Android clients will then need the new token.

## Safety / source-control note

Do not commit pairing tokens, private keys, signing material or local runtime-state files. Historical CI logs should stay in GitHub Actions artifacts rather than being copied into the repository.
