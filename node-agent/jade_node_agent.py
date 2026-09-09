#!/usr/bin/env python3
"""Jade Genesis Node Runtime 0.1.3 entrypoint.

The 0.1.2 runtime core is kept as a stable module while 0.1.3 layers the
Shared Genesis State durable VPS replica on top. The wire protocol remains
jade-genesis-node/0.0.6 for already paired Android clients.
"""

from __future__ import annotations

import jade_node_runtime_core as core
from shared_genesis_state import run_shared_state_sync, shared_state_status

VERSION = "0.1.3"
PROTOCOL = core.PROTOCOL

_original_execute = core.execute_allowlisted_task
_original_health = core.health_payload
_original_runtime = core.runtime_payload


def _execute_allowlisted_task(
    task_kind: str,
    payload: str,
    iterations: int,
    config: dict,
):
    if task_kind == "shared_state_sync":
        return run_shared_state_sync(payload, config)
    return _original_execute(task_kind, payload, iterations, config)


def _health_payload(config: dict, store=None) -> dict:
    result = _original_health(config, store)
    if str(config.get("node_kind", "")).upper() == "VPS":
        capabilities = list(result.get("capabilities", []))
        for capability in (
            "shared_genesis_state_v1",
            "durable_state_replica_v1",
            "shared_state_sync",
        ):
            if capability not in capabilities:
                capabilities.append(capability)
        result["capabilities"] = capabilities
        result["shared_state"] = shared_state_status()
    result["agent_version"] = VERSION
    return result


def _runtime_payload(config: dict) -> dict:
    result = _original_runtime(config)
    result["runtime_version"] = VERSION
    if str(config.get("node_kind", "")).upper() == "VPS":
        result["shared_state"] = shared_state_status()
    return result


# Patch the core module before its main loop constructs handlers/stores. Functions
# defined in the core resolve these globals dynamically, so synchronous and async
# task paths both see the 0.1.3 extension.
core.VERSION = VERSION
core.ALLOWED_TASKS = tuple(core.ALLOWED_TASKS) + ("shared_state_sync",)
core.execute_allowlisted_task = _execute_allowlisted_task
core.health_payload = _health_payload
core.runtime_payload = _runtime_payload


if __name__ == "__main__":
    raise SystemExit(core.main())
