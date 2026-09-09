#!/usr/bin/env python3
"""Jade Genesis Node Runtime 0.1.6 entrypoint.

The 0.1.2 runtime core is kept as a stable module while this wrapper layers the
Shared Genesis State replica, bounded VPS Night Cycle supervisor, Night
Learning Lab and role-aware Cognitive Brain profiles on top. The wire protocol
remains compatible with already paired Android clients.
"""

from __future__ import annotations

import jade_node_runtime_core as core
from brain_profiles import brain_profiles_status, run_brain_chat_profiled
from shared_genesis_state import run_shared_state_sync, shared_state_status
from vps_night_cycle import start_supervisor, stop_supervisor, supervisor_status

VERSION = "0.1.6"
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


def _profiled_brain_chat(payload: str, config: dict):
    return run_brain_chat_profiled(payload, config, core)


def _health_payload(config: dict, store=None) -> dict:
    result = _original_health(config, store)
    capabilities = list(result.get("capabilities", []))
    if "cognitive_brain_profiles_v1" not in capabilities:
        capabilities.append("cognitive_brain_profiles_v1")
    result["capabilities"] = capabilities
    result["brain_profiles"] = brain_profiles_status(config, core)

    if str(config.get("node_kind", "")).upper() == "VPS":
        capabilities = list(result.get("capabilities", []))
        for capability in (
            "shared_genesis_state_v1",
            "durable_state_replica_v1",
            "shared_state_sync",
            "vps_night_cycle_supervisor_v1",
            "night_learning_lab_v1",
        ):
            if capability not in capabilities:
                capabilities.append(capability)
        result["capabilities"] = capabilities
        result["shared_state"] = shared_state_status()
        result["night_cycle_supervisor"] = supervisor_status(config)
    result["agent_version"] = VERSION
    return result


def _runtime_payload(config: dict) -> dict:
    result = _original_runtime(config)
    result["runtime_version"] = VERSION
    result["brain_profiles"] = brain_profiles_status(config, core)
    if str(config.get("node_kind", "")).upper() == "VPS":
        result["shared_state"] = shared_state_status()
        result["night_cycle_supervisor"] = supervisor_status(config)
    return result


def _runtime_started(config: dict) -> None:
    start_supervisor(config, logger=core.log_event)


def _runtime_stopping() -> None:
    stop_supervisor()


# Patch the core module before its main loop constructs handlers/stores. Functions
# defined in the core resolve these globals dynamically, so synchronous and async
# task paths both see the 0.1.6 extension.
core.VERSION = VERSION
core.ALLOWED_TASKS = tuple(core.ALLOWED_TASKS) + ("shared_state_sync",)
core.run_brain_chat = _profiled_brain_chat
core.execute_allowlisted_task = _execute_allowlisted_task
core.health_payload = _health_payload
core.runtime_payload = _runtime_payload
core.runtime_started = _runtime_started
core.runtime_stopping = _runtime_stopping

# Compatibility exports used by CI/tests and by simple tooling that imported the
# old monolithic entrypoint directly.
ALLOWED_TASKS = core.ALLOWED_TASKS
cpu_load_percent = core.cpu_load_percent
gpu_telemetry = core.gpu_telemetry
AsyncTaskStore = core.AsyncTaskStore
health_payload = core.health_payload
runtime_payload = core.runtime_payload
execute_allowlisted_task = core.execute_allowlisted_task


if __name__ == "__main__":
    raise SystemExit(core.main())
