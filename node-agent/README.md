# Jade Genesis — Distributed Node Runtime 0.1.3

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

Allow-listed tasks include `genesis_probe`, `text_analysis`, `memory_consolidation`, `brain_chat`, `screen_analyze`, `vision_analyze` and, on VPS replicas, `shared_state_sync`. No arbitrary remote shell/system command is exposed.

## Shared Genesis State v1

Runtime 0.1.3 adds an optional durable operational-state replica on nodes configured as `VPS`. It advertises:

- `shared_genesis_state_v1`
- `durable_state_replica_v1`
- `shared_state_sync`

The replica stores a bounded, versioned event stream and a compact latest-entity snapshot in `~/.jade-genesis/genesis-state.json`, with a backup copy. Sync is idempotent by event ID and bound to one Jade identity. A VPS replica does **not** become the sole owner of Jade's identity.

The Pixel side keeps its own local cache and outbox. If the VPS is temporarily unavailable, unsynced operational snapshots remain on the phone and are retried later. Runtime 0.1.3 provides the durable-state foundation; autonomous Night Cycle execution is intentionally a later stage.

## Resource and model telemetry

Runtime 0.1.3 preserves Resource Intelligence v3 from 0.1.2: CPU load, task count, RAM, NVIDIA GPU/VRAM telemetry when available, Ollama model state and measured generation throughput. Existing vision and asynchronous-task capabilities remain available.

## Useful local commands

```powershell
py jade_node_agent.py --show-config
py jade_node_agent.py --probe-ollama
py jade_node_agent.py --show-token
```

Use `--reset-token` only when intentionally rotating the pairing token; existing paired Android clients will then need the new token.
