#!/usr/bin/env python3
"""Jade Genesis Node Runtime 0.1.8 entrypoint.

The stable runtime core stays separate while this wrapper layers Shared Genesis
State, bounded VPS Night Learning, Adaptive Strategy Registry, Verifiable Task
Ledger, SkillSpec, the restricted Procedure Runtime, the persistent Skill
Registry and the 0.1.21 verified Skill Synthesis Loop on top.

The wire protocol remains jade-genesis-node/0.0.6 for already paired Android
clients. Procedure execution, Skill Registry mutation and Skill Synthesis are
still deliberately not exposed as arbitrary remote tasks. 0.1.21 first proves
the local causal acquisition path before any broader trigger surface is opened.

0.1.21 also contains a deliberately cheap demonstration dispatch: a structured
brain_chat request carrying an exact task_family + skill_input consults the
production Skill route before any Ollama model listing/chat call. This proves
causal reuse without changing the wire protocol. Moving this dispatch up into
the task router before node/lease selection is explicit post-proof debt.
"""

from __future__ import annotations

import json
import threading
import time

import jade_node_runtime_core as core
from adaptive_strategy_registry import adaptive_strategy_registry_status
from adaptive_vps_night_cycle import start_supervisor, stop_supervisor, supervisor_status
from brain_profiles import brain_profiles_status, run_brain_chat_profiled
from learning_environment import learning_environment_status
from learning_stores import (
    open_archive_ledger,
    open_archive_skill_registry,
    open_workshop_goals,
    zone_store_status,
)
from procedure_runtime import procedure_runtime_status
from shared_genesis_state import run_shared_state_sync, shared_state_status
from skill_registry import skill_registry_status
from skill_spec import skill_spec_status
from skill_synthesis_loop import skill_synthesis_status

VERSION = "0.1.8"
PROTOCOL = core.PROTOCOL

_original_execute = core.execute_allowlisted_task
_original_health = core.health_payload
_original_runtime = core.runtime_payload
_original_ollama_models = core.ollama_models
_original_json_request = core._json_request

_OLLAMA_COUNTER_LOCK = threading.RLock()
_OLLAMA_COUNTERS = {
    "model_list_calls": 0,
    "chat_calls": 0,
}


def ollama_call_counters() -> dict:
    with _OLLAMA_COUNTER_LOCK:
        model_list = int(_OLLAMA_COUNTERS["model_list_calls"])
        chat = int(_OLLAMA_COUNTERS["chat_calls"])
        return {
            "model_list_calls": model_list,
            "chat_calls": chat,
            "total_calls": model_list + chat,
            "process_local_counter": True,
        }


def _counted_ollama_models(*args, **kwargs):
    with _OLLAMA_COUNTER_LOCK:
        _OLLAMA_COUNTERS["model_list_calls"] += 1
    return _original_ollama_models(*args, **kwargs)


def _counted_json_request(url: str, *args, **kwargs):
    if "/api/chat" in str(url):
        with _OLLAMA_COUNTER_LOCK:
            _OLLAMA_COUNTERS["chat_calls"] += 1
    return _original_json_request(url, *args, **kwargs)


def _execute_allowlisted_task(
    task_kind: str,
    payload: str,
    iterations: int,
    config: dict,
):
    if task_kind == "shared_state_sync":
        return run_shared_state_sync(payload, config)
    return _original_execute(task_kind, payload, iterations, config)


def _structured_skill_request(context: dict) -> tuple[str, str, object] | None:
    family = str(context.get("task_family", "")).strip()
    if not family or "skill_input" not in context:
        return None
    identity = context.get("identity")
    if not isinstance(identity, dict):
        return None
    jade_id = str(identity.get("jade_id", "")).strip()
    if not jade_id:
        return None
    return jade_id, family, context.get("skill_input")


def _profiled_brain_chat(payload: str, config: dict):
    started = time.perf_counter_ns()
    try:
        context = json.loads(payload)
    except json.JSONDecodeError:
        context = None

    if isinstance(context, dict):
        structured = _structured_skill_request(context)
        if structured is not None:
            identity_id, family, skill_input = structured
            before = ollama_call_counters()
            registry = open_archive_skill_registry()
            try:
                recall = registry.execute_for_family(
                    identity_id,
                    family,
                    skill_input,
                )
            except LookupError:
                recall = None
            if recall is not None:
                after = ollama_call_counters()
                duration_ms = int((time.perf_counter_ns() - started) // 1_000_000)
                result_value = recall["execution"]["result"]
                response = {
                    "text": json.dumps(
                        result_value,
                        ensure_ascii=False,
                        separators=(",", ":"),
                        sort_keys=True,
                    ),
                    "backend": "verified_skill_registry",
                    "model": "",
                    "brain_profile": "skill",
                    "brain_profile_policy": "skill_dispatch_demo_v1",
                    "selection_reason": "exact_task_family_before_ollama",
                    "degraded": False,
                    "local": True,
                    "operation": str(context.get("operation", "answer")),
                    "task_family": family,
                    "skill_id": recall["selected_skill_id"],
                    "skill_version": recall["selected_skill_version"],
                    "skill_spec_sha256": recall["selected_spec_sha256"],
                    "verified_learned_skill": recall["verified_learned_skill"],
                    "ollama_calls_before": before,
                    "ollama_calls_after": after,
                    "ollama_calls_delta": after["total_calls"] - before["total_calls"],
                    "router_level_skill_dispatch_pending": True,
                }
                return (
                    json.dumps(response, ensure_ascii=False, separators=(",", ":")),
                    duration_ms,
                )

    return run_brain_chat_profiled(payload, config, core)


def _learning_status_payload() -> dict:
    ledger = open_archive_ledger()
    goals = open_workshop_goals()
    return {
        "verifiable_task_ledger": ledger.status(),
        "skill_synthesis": skill_synthesis_status(goals),
        "learning_environment": learning_environment_status(),
        "learning_stores": zone_store_status(),
    }


def _health_payload(config: dict, store=None) -> dict:
    result = _original_health(config, store)
    capabilities = list(result.get("capabilities", []))
    for capability in (
        "cognitive_brain_profiles_v1",
        "correction_exact_json_v1",
        "correction_restricted_procedure_v1",
        "verified_skill_pre_ollama_dispatch_v1",
        "ollama_call_counter_v1",
    ):
        if capability not in capabilities:
            capabilities.append(capability)
    result["capabilities"] = capabilities
    result["brain_profiles"] = brain_profiles_status(config, core)
    result["ollama_calls"] = ollama_call_counters()
    result["skill_dispatch"] = {
        "enabled_for_structured_brain_chat": True,
        "exact_task_family_only": True,
        "before_ollama": True,
        "router_level_dispatch_pending": True,
        "router_level_dispatch_reason": "0.1.21 proof first; avoid widening routing changes before causal acquisition is demonstrated",
    }

    if str(config.get("node_kind", "")).upper() == "VPS":
        capabilities = list(result.get("capabilities", []))
        for capability in (
            "shared_genesis_state_v1",
            "durable_state_replica_v1",
            "shared_state_sync",
            "vps_night_cycle_supervisor_v1",
            "night_learning_lab_v1",
            "adaptive_strategy_registry_v1",
            "verifiable_task_ledger_v1",
            "skill_spec_v1",
            "procedure_runtime_v1",
            "skill_registry_v1",
            "skill_synthesis_loop_v1",
            "learning_environment_v1",
            "learning_workshop_v1",
            "correction_sealed_skill_exam_v1",
            "skill_attribution_v1",
        ):
            if capability not in capabilities:
                capabilities.append(capability)
        result["capabilities"] = capabilities
        result["shared_state"] = shared_state_status()
        result["night_cycle_supervisor"] = supervisor_status(config)
        result["adaptive_strategy_registry"] = adaptive_strategy_registry_status()
        learning = _learning_status_payload()
        result["verifiable_task_ledger"] = learning["verifiable_task_ledger"]
        result["skill_spec"] = skill_spec_status()
        result["procedure_runtime"] = procedure_runtime_status()
        result["skill_registry"] = skill_registry_status()
        result["skill_synthesis"] = learning["skill_synthesis"]
        result["learning_environment"] = learning["learning_environment"]
        result["learning_stores"] = learning["learning_stores"]
    result["agent_version"] = VERSION
    return result


def _runtime_payload(config: dict) -> dict:
    result = _original_runtime(config)
    result["runtime_version"] = VERSION
    result["brain_profiles"] = brain_profiles_status(config, core)
    result["ollama_calls"] = ollama_call_counters()
    result["skill_dispatch"] = {
        "enabled_for_structured_brain_chat": True,
        "exact_task_family_only": True,
        "before_ollama": True,
        "router_level_dispatch_pending": True,
    }
    if str(config.get("node_kind", "")).upper() == "VPS":
        result["shared_state"] = shared_state_status()
        result["night_cycle_supervisor"] = supervisor_status(config)
        result["adaptive_strategy_registry"] = adaptive_strategy_registry_status()
        learning = _learning_status_payload()
        result["verifiable_task_ledger"] = learning["verifiable_task_ledger"]
        result["skill_spec"] = skill_spec_status()
        result["procedure_runtime"] = procedure_runtime_status()
        result["skill_registry"] = skill_registry_status()
        result["skill_synthesis"] = learning["skill_synthesis"]
        result["learning_environment"] = learning["learning_environment"]
        result["learning_stores"] = learning["learning_stores"]
    return result


def _runtime_started(config: dict) -> None:
    start_supervisor(config, logger=core.log_event)


def _runtime_stopping() -> None:
    stop_supervisor()


# Patch the core module before its main loop constructs handlers/stores. All
# Ollama model listing and chat calls that go through the runtime core are counted
# before higher-level code uses them.
core.VERSION = VERSION
core.ollama_models = _counted_ollama_models
core._json_request = _counted_json_request
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
