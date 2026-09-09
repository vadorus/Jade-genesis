# Jade Genesis — Distributed Node Runtime 0.1.5

Node Runtime connects a PC or VPS to the same logical Jade Genesis identity. The runtime stays dependency-free and keeps the compatible wire protocol `jade-genesis-node/0.0.6`.

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

All Jade requests use the existing authenticated Node Runtime transport. Android requires an authorized Tailscale route for authenticated node traffic.

Endpoints include:

- `GET /health`
- `GET /runtime`
- `GET /diagnostics`
- `GET /tasks/<task_id>`
- `POST /task`
- `POST /tasks`

Allow-listed tasks include `genesis_probe`, `text_analysis`, `memory_consolidation`, `brain_chat`, `screen_analyze`, `vision_analyze` and, on VPS replicas, `shared_state_sync`. The Night Learning Lab is intentionally **not** a remotely invokable task. No arbitrary remote shell/system command is exposed.

## Shared Genesis State v1

Runtime 0.1.5 keeps the durable operational-state replica on nodes configured as `VPS`. It advertises:

- `shared_genesis_state_v1`
- `durable_state_replica_v1`
- `shared_state_sync`
- `vps_night_cycle_supervisor_v1`
- `night_learning_lab_v1`

The replica stores a bounded, versioned event stream and a compact latest-entity snapshot in `~/.jade-genesis/genesis-state.json`, with a backup copy. Sync is idempotent by event ID and bound to one Jade identity. A VPS replica does **not** become the sole owner of Jade's identity.

The Pixel side keeps its own local cache and outbox. If the VPS is temporarily unavailable, unsynced operational snapshots remain on the phone and are retried later.

## VPS-supervised Night Cycle + Night Learning Lab

On a node configured as `VPS`, Runtime 0.1.5 starts a bounded local supervisor. It waits until the latest synchronized Pixel snapshot has been inactive for at least two hours, then runs at most once per twenty-hour protection window. The supervisor reviews the durable Shared Genesis State replica, the Memory v2 cursor, the latest Runtime Eval summary and the Evolution Engine candidate summary. Its journal is bounded to 30 runs and stored with a backup in `~/.jade-genesis/night-cycle-supervisor.json`.

The Night Learning Lab turns measured Runtime Eval signals into a small review pipeline: at most 3 targeted research questions, 6 public evidence items, 4 falsifiable hypotheses, 4 experiment proposals and 4 improvement candidates. Public research is optional and fail-soft: it uses one fixed HTTPS provider with strict timeout/response limits, and a provider failure never creates invented evidence. Research queries are derived from structured runtime metrics rather than raw user text.

Experiments are plans only. They require paired scenarios, a frozen champion when relevant and explicit approval before any future promotion. Improvement artifacts remain `CANDIDATE` review items; they cannot activate themselves, rewrite production code, change compiled SafetyPolicy, mutate Pixel memory or execute shell commands.

The VPS writes `vps_learning_snapshot`, `vps_maintenance_snapshot` and `vps_night_cycle_report` events back into Shared Genesis State so the Pixel receives them on its next normal sync. Android validates learning snapshots fail-closed and rejects any snapshot requesting automatic experiment execution, promotion, production-code rewrite or shell execution.

## Resource and model telemetry

Runtime 0.1.5 preserves Resource Intelligence v3 from 0.1.2: CPU load, task count, RAM, NVIDIA GPU/VRAM telemetry when available, Ollama model state and measured generation throughput. Existing vision and asynchronous-task capabilities remain available.

## Useful local commands

```powershell
py jade_node_agent.py --show-config
py jade_node_agent.py --probe-ollama
py jade_node_agent.py --show-token
```

Use `--reset-token` only when intentionally rotating the pairing token; existing paired Android clients will then need the new token.
