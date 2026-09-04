# Jade Genesis — Distributed Node Runtime 0.0.6

This runtime turns a PC into a compute node for the same Jade Genesis identity running across devices.

## Start on Windows

Open PowerShell in the folder containing `jade_node_agent.py` and run:

```powershell
py jade_node_agent.py
```

The runtime prints the LAN IP, port and pairing token to enter in Jade Android > Node Manager.

The node ID, token and port remain stored in:

`%USERPROFILE%\.jade-genesis\node-agent.json`

Upgrading from 0.0.5 to 0.0.6 keeps the same node identity and token unless `--reset-token` is explicitly used.

## V0.0.6 protocol

Protocol: `jade-genesis-node/0.0.6`

Endpoints:

- `GET /health`
- `POST /task`

Allowed tasks:

- `genesis_probe` — bounded SHA-256 compute probe
- `text_analysis` — deterministic text metrics and SHA-256 digest
- `memory_consolidation` — deterministic memory dedupe, themes and contradiction signals

No arbitrary shell or system command execution is exposed.

## Persistent task queue

The Android side now tracks task lifecycle as PENDING, RUNNING, COMPLETED or FAILED. Interrupted RUNNING entries are recovered as FAILED on restart so Jade does not silently forget unfinished work.

## Self test

```powershell
py jade_node_agent.py --self-test
```

Expected output:

`JADE NODE RUNTIME 0.0.6 SELF-TEST OK`
