"""The 0.1.21 night-cycle crank for Jade's first real skill acquisition.

This is intentionally narrow plumbing, not a second learning architecture.
It connects the existing pieces for exactly one family:

real brain traffic -> pre-partitioned sealed datasets -> external hash receipts
-> gap -> Ollama CODE teacher -> SkillSynthesisLoop -> Ledger final exam
-> verified production route.

The cycle stays inert unless learning_trial_enabled is explicitly true and the
required reserve of three attested, unconsumed datasets already exists.
"""

from __future__ import annotations

from typing import Any

import jade_node_runtime_core as core
from first_learning_family import (
    INPUT_CONTRACT,
    OUTPUT_CONTRACT,
    TASK_FAMILY,
    family_status,
)
from learning_environment import append_attribution_event
from learning_stores import open_archive_skill_registry, open_workshop_goals
from learning_trial_protocol import (
    MIN_READY_DATASETS,
    attestation_for_dataset,
    open_attested_gap,
    publish_seal_attestation,
    ready_dataset_pool,
    run_attested_learning,
)
from ollama_skill_teacher import OllamaSkillTeacher

GOAL_ID = "first-real-normalize-label-v1"


def _truthy(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _external_attestations(config: dict[str, Any]) -> list[dict[str, str]]:
    raw = config.get("learning_trial_seal_attestations", [])
    if not isinstance(raw, list):
        return []
    result = []
    for item in raw[:32]:
        if not isinstance(item, dict):
            continue
        dataset_id = str(item.get("dataset_id", "")).strip()
        sealed_hash = str(item.get("sealed_set_sha256", "")).strip().lower()
        reference = str(item.get("external_publication_ref", "")).strip()
        if dataset_id and len(sealed_hash) == 64 and reference.startswith("external:"):
            result.append(
                {
                    "dataset_id": dataset_id,
                    "sealed_set_sha256": sealed_hash,
                    "external_publication_ref": reference,
                }
            )
    return result


def ingest_configured_attestations(
    identity_id: str,
    config: dict[str, Any],
    *,
    now_ms: int | None = None,
) -> dict[str, Any]:
    accepted = 0
    existing = 0
    rejected = 0
    for item in _external_attestations(config):
        current = attestation_for_dataset(
            identity_id,
            item["dataset_id"],
            item["sealed_set_sha256"],
        )
        if current is not None:
            existing += 1
            continue
        try:
            publish_seal_attestation(
                identity_id,
                item["dataset_id"],
                item["sealed_set_sha256"],
                external_publication_ref=item["external_publication_ref"],
                now_ms=now_ms,
            )
            accepted += 1
        except (ValueError, PermissionError, RuntimeError):
            rejected += 1
    return {
        "configured": len(_external_attestations(config)),
        "accepted": accepted,
        "already_present": existing,
        "rejected": rejected,
    }


def _existing_goal(identity_id: str) -> dict[str, Any] | None:
    goals = open_workshop_goals()
    with goals.lock:
        state = goals._load()
        goals._bind_identity(state, identity_id)
        for item in state.get("goals", {}).values():
            if not isinstance(item, dict):
                continue
            if str(item.get("goal_id", "")) == GOAL_ID:
                return dict(item)
    return None


def _gap_score(identity_id: str) -> dict[str, Any]:
    status = family_status(identity_id)
    frequency = max(0, int(status.get("dataset_count", 0)) * int(status.get("cases_per_dataset", 0)))
    estimated_model_cost = 1.0
    coverage = 1.0 if open_archive_skill_registry().selected_skill(identity_id, TASK_FAMILY) else 0.0
    score = float(frequency) * estimated_model_cost * (1.0 - coverage)
    return {
        "frequency": frequency,
        "estimated_model_cost": estimated_model_cost,
        "coverage": coverage,
        "score": score,
        "formula": "frequency*cost*(1-coverage)",
    }


def run_first_learning_cycle(
    config: dict[str, Any],
    identity_id: str,
    *,
    now_ms: int | None = None,
) -> dict[str, Any]:
    identity = str(identity_id or "").strip()
    if not identity:
        return {"status": "BLOCKED", "reason": "identity_unbound", "task_family": TASK_FAMILY}
    if not _truthy(config.get("learning_trial_enabled", False)):
        return {
            "status": "DISABLED",
            "reason": "learning_trial_enabled_false",
            "task_family": TASK_FAMILY,
            "automatic_learning": False,
        }

    registry = open_archive_skill_registry()
    selected = registry.selected_skill(identity, TASK_FAMILY)
    if selected is not None:
        return {
            "status": "ALREADY_COVERED",
            "task_family": TASK_FAMILY,
            "skill_id": selected["skill_id"],
            "skill_version": selected["skill_version"],
            "gap": _gap_score(identity),
        }

    attestation_ingest = ingest_configured_attestations(identity, config, now_ms=now_ms)
    pool = ready_dataset_pool(identity, TASK_FAMILY)
    gap = _gap_score(identity)
    if len(pool) < MIN_READY_DATASETS:
        return {
            "status": "WAITING_FOR_DATASET_RESERVE",
            "task_family": TASK_FAMILY,
            "ready_attested_dataset_count": len(pool),
            "minimum_ready_datasets": MIN_READY_DATASETS,
            "attestations": attestation_ingest,
            "gap": gap,
            "teacher_called": False,
        }

    goal = _existing_goal(identity)
    if goal is None:
        opened = open_attested_gap(
            identity,
            GOAL_ID,
            TASK_FAMILY,
            INPUT_CONTRACT,
            OUTPUT_CONTRACT,
            reason="Repeated real label normalization is uncovered and currently falls back to a model.",
            min_ready_datasets=MIN_READY_DATASETS,
            now_ms=now_ms,
        )
        goal_state = opened["state"]
    else:
        goal_state = str(goal.get("state", ""))

    if goal_state == "RETAINED":
        return {
            "status": "ALREADY_RETAINED",
            "task_family": TASK_FAMILY,
            "gap": _gap_score(identity),
        }
    if goal_state not in {"OPEN", "VISIBLE_TESTING"}:
        return {
            "status": "GOAL_NOT_RETRYABLE",
            "task_family": TASK_FAMILY,
            "goal_state": goal_state,
            "ready_attested_dataset_count": len(pool),
            "teacher_called": False,
        }

    teacher = OllamaSkillTeacher(config, core)
    append_attribution_event(
        "night_skill_learning_triggered",
        {
            "identity_id": identity,
            "task_family": TASK_FAMILY,
            "goal_id": GOAL_ID,
            "gap": gap,
            "ready_attested_dataset_count": len(pool),
            "minimum_ready_datasets": MIN_READY_DATASETS,
        },
        now_ms=now_ms,
    )
    result = run_attested_learning(
        identity,
        GOAL_ID,
        teacher,
        teacher_id="ollama-code-teacher-v1",
        source_model="ollama-code-profile",
        max_candidates=4,
        now_ms=now_ms,
    )
    return {
        "status": result.get("state", "UNKNOWN"),
        "task_family": TASK_FAMILY,
        "goal_id": GOAL_ID,
        "gap": gap,
        "teacher_called": teacher.call_count > 0,
        "teacher": teacher.status(),
        "result": result,
        "router_level_skill_dispatch_pending": True,
    }


def skill_learning_cycle_status(config: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "enabled": _truthy(config.get("learning_trial_enabled", False)),
        "task_family": TASK_FAMILY,
        "goal_id": GOAL_ID,
        "minimum_ready_datasets": MIN_READY_DATASETS,
        "requires_external_hash_attestation": True,
        "teacher": "ollama_code_profile",
        "teacher_trigger": "vps_night_cycle_only",
        "remote_learning_task_exposed": False,
        "router_level_skill_dispatch_pending": True,
    }
