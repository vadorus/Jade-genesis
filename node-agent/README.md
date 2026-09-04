# Jade Genesis — Distributed Node Runtime 0.0.5

This runtime turns a PC into a compute node for the same Jade Genesis identity running across devices.

## Start on Windows

Open PowerShell in the folder containing `jade_node_agent.py` and run:

```powershell
py jade_node_agent.py
```

The runtime prints the LAN IP, port and pairing token to enter in Jade Android > Node Manager.

The node ID, token and port remain stored in:

`%USERPROFILE%\.jade-genesis\node-agent.json`

So upgrading from 0.0.4 to 0.0.5 keeps the same node identity and token unless `--reset-token` is explicitly used.

## V0.0.5 protocol

Protocol: `jade-genesis-node/0.0.5`

Endpoints:

- `GET /health`
- `POST /task`

Allowed tasks:

- `genesis_probe` — bounded SHA-256 compute probe
- `text_analysis` — deterministic text metrics and SHA-256 digest

No arbitrary shell or system command execution is exposed.

## Self test

```powershell
py jade_node_agent.py --self-test
```

Expected output:

`JADE NODE RUNTIME 0.0.5 SELF-TEST OK`
