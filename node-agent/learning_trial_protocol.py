"""Launch protocol for the first real Jade Genesis 0.1.21 acquisition.

This module does not add another learning engine. It is the gate around the
existing SkillSynthesisLoop used by production/night-cycle plumbing.

Before a teacher may run:
- the dataset is already sealed;
- the exact sealed_set_sha256 has been attested with a reference outside the
  learning workshop (operator/GitHub/runbook receipt);
- the family owns a reserve pool of multiple sealed, attested, unconsumed
  datasets so one failed one-shot exam never motivates unsealing/reuse.

The immutable attribution journal timestamps attestation before teacher_start.
"""

from __future__ import annotations

from typing import Any

from learning_environment import (
    ARCHIVE_ATTRIBUTION_LOG_PATH,
    append_attribution_event,
    read_attribution_events,
)
from learning_stores import (
    open_archive_ledger,
    open_archive_skill_registry,
    open_skill_synthesis_loop,
    open_workshop_goals,
)

MIN_READY_DATASETS = 3


def _clean(value: Any, name: str, limit: int = 240) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:limit]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _dataset_summaries(identity_id: str, task_family: str) -> list[dict[str, Any]]:
    """Read bounded summaries from the canonical archive ledger.

    This is deliberately inside the verifier process. It returns no case input,
    expected output or seal nonce.
    """

    ledger = open_archive_ledger()
    family = _clean(task_family, "task_family", 160)
    with ledger.lock:
        state = ledger._load()
        ledger._bind_identity(state, identity_id)
        result = []
        for dataset in state.get("datasets", {}).values():
            if not isinstance(dataset, dict):
                continue
            if str(dataset.get("task_family", "")) != family:
                continue
            result.append(
                {
                    "dataset_id": str(dataset.get("dataset_id", "")),
                    "task_family": family,
                    "sealed": dataset.get("sealed") is True,
                    "sealed_at": max(0, int(dataset.get("sealed_at", 0))),
                    "sealed_set_sha256": str(dataset.get("sealed_set_sha256", "")),
                    "sealed_exam_consumed": isinstance(dataset.get("sealed_exam"), dict),
                    "sealed_test_count": ledger._partition_count(dataset, "SEALED_TEST"),
                    "case_count": len(dataset.get("cases", {})),
                    "sealed_test_inputs_exposed": False,
                    "sealed_test_answers_exposed": False,
                }
            )
        result.sort(key=lambda item: (item["sealed_at"], item["dataset_id"]))
        return result


def publish_seal_attestation(
    identity_id: str,
    dataset_id: str,
    sealed_set_sha256: str,
    *,
    external_publication_ref: str,
    now_ms: int | None = None,
) -> dict[str, Any]:
    """Record that an already-sealed hash was published before learning starts.

    The reference is intentionally opaque to Jade. For the formal real trial it
    must identify evidence outside the learning workshop/runtime state (for
    example a GitHub issue/comment, operator runbook entry or independent log).
    """

    identity = _clean(identity_id, "identity_id", 160)
    dataset = _clean(dataset_id, "dataset_id", 160)
    supplied_hash = str(sealed_set_sha256 or "").strip().lower()
    reference = _clean(external_publication_ref, "external_publication_ref")
    if not reference.lower().startswith("external:"):
        raise ValueError("seal_attestation_must_reference_external_evidence")

    ledger = open_archive_ledger()
    manifest = ledger.sealed_manifest(identity, dataset)
    if manifest.get("sealed") is not True:
        raise PermissionError("seal_attestation_requires_sealed_dataset")
    if manifest.get("sealed_exam_consumed"):
        raise PermissionError("seal_attestation_dataset_already_consumed")
    expected = str(manifest.get("sealed_set_sha256", "")).lower()
    if not expected or supplied_hash != expected:
        raise ValueError("seal_attestation_hash_mismatch")

    event = append_attribution_event(
        "sealed_dataset_attested",
        {
            "identity_id": identity,
            "dataset_id": dataset,
            "task_family": manifest.get("task_family", ""),
            "sealed_set_sha256": expected,
            "sealed_at": manifest.get("sealed_at", 0),
            "external_publication_ref": reference,
            "sealed_test_inputs_exposed": False,
            "sealed_test_answers_exposed": False,
        },
        path=ARCHIVE_ATTRIBUTION_LOG_PATH,
        now_ms=now_ms,
    )
    if int(event["occurred_at"]) < int(manifest.get("sealed_at", 0)):
        raise ValueError("seal_attestation_cannot_predate_seal")
    return {
        "dataset_id": dataset,
        "sealed_set_sha256": expected,
        "external_publication_ref": reference,
        "attested_at": event["occurred_at"],
        "attribution_event_sha256": event["event_sha256"],
    }


def attestation_for_dataset(
    identity_id: str,
    dataset_id: str,
    sealed_set_sha256: str,
) -> dict[str, Any] | None:
    identity = _clean(identity_id, "identity_id", 160)
    dataset = _clean(dataset_id, "dataset_id", 160)
    expected_hash = str(sealed_set_sha256 or "").strip().lower()
    for event in read_attribution_events(path=ARCHIVE_ATTRIBUTION_LOG_PATH):
        if event.get("event_kind") != "sealed_dataset_attested":
            continue
        payload = event.get("payload", {})
        if not isinstance(payload, dict):
            continue
        if (
            str(payload.get("identity_id", "")) == identity
            and str(payload.get("dataset_id", "")) == dataset
            and str(payload.get("sealed_set_sha256", "")).lower() == expected_hash
        ):
            return {
                "attested_at": int(event.get("occurred_at", 0)),
                "external_publication_ref": str(payload.get("external_publication_ref", "")),
                "attribution_event_sha256": str(event.get("event_sha256", "")),
            }
    return None


def ready_dataset_pool(
    identity_id: str,
    task_family: str,
) -> list[dict[str, Any]]:
    ready = []
    for item in _dataset_summaries(identity_id, task_family):
        if not item["sealed"] or item["sealed_exam_consumed"]:
            continue
        attestation = attestation_for_dataset(
            identity_id,
            item["dataset_id"],
            item["sealed_set_sha256"],
        )
        if attestation is None:
            continue
        if int(attestation["attested_at"]) < int(item["sealed_at"]):
            continue
        ready.append({**item, "attestation": attestation})
    return ready


def open_attested_gap(
    identity_id: str,
    goal_id: str,
    task_family: str,
    input_contract: dict[str, Any],
    output_contract: dict[str, Any],
    *,
    reason: str,
    min_ready_datasets: int = MIN_READY_DATASETS,
    now_ms: int | None = None,
) -> dict[str, Any]:
    pool = ready_dataset_pool(identity_id, task_family)
    required = max(2, int(min_ready_datasets))
    if len(pool) < required:
        raise PermissionError("learning_trial_requires_dataset_reserve")
    selected = pool[0]
    goals = open_workshop_goals()
    return goals.open_gap(
        identity_id,
        goal_id,
        task_family,
        selected["dataset_id"],
        input_contract,
        output_contract,
        reason=reason,
        registry=open_archive_skill_registry(),
        ledger=open_archive_ledger(),
        now_ms=now_ms,
    )


def run_attested_learning(
    identity_id: str,
    goal_id: str,
    teacher: Any,
    *,
    teacher_id: str,
    source_model: str = "",
    max_candidates: int = 4,
    now_ms: int | None = None,
) -> dict[str, Any]:
    goals = open_workshop_goals()
    goal = goals.get(identity_id, goal_id)
    ledger = open_archive_ledger()
    manifest = ledger.sealed_manifest(identity_id, goal["dataset_id"])
    attestation = attestation_for_dataset(
        identity_id,
        goal["dataset_id"],
        manifest.get("sealed_set_sha256", ""),
    )
    if attestation is None:
        raise PermissionError("teacher_requires_preexisting_seal_attestation")
    if int(attestation["attested_at"]) < int(manifest.get("sealed_at", 0)):
        raise PermissionError("teacher_requires_post_seal_attestation")

    start_event = append_attribution_event(
        "skill_teacher_started",
        {
            "identity_id": _clean(identity_id, "identity_id", 160),
            "goal_id": _clean(goal_id, "goal_id", 160),
            "dataset_id": goal["dataset_id"],
            "task_family": goal["task_family"],
            "sealed_set_sha256": manifest.get("sealed_set_sha256", ""),
            "attestation_event_sha256": attestation["attribution_event_sha256"],
            "teacher_id": _clean(teacher_id, "teacher_id", 160),
        },
        path=ARCHIVE_ATTRIBUTION_LOG_PATH,
        now_ms=now_ms,
    )
    if int(start_event["occurred_at"]) < int(attestation["attested_at"]):
        raise RuntimeError("teacher_started_before_seal_attestation")

    loop = open_skill_synthesis_loop()
    result = loop.learn(
        identity_id,
        goal_id,
        teacher,
        teacher_id=teacher_id,
        source_model=source_model,
        max_candidates=max_candidates,
        now_ms=now_ms,
    )
    return {
        **result,
        "seal_attestation_verified": True,
        "sealed_dataset_reserve_after_run": len(
            ready_dataset_pool(identity_id, goal["task_family"])
        ),
    }


def learning_trial_status(identity_id: str, task_family: str) -> dict[str, Any]:
    pool = ready_dataset_pool(identity_id, task_family)
    return {
        "task_family": task_family,
        "ready_attested_dataset_count": len(pool),
        "minimum_ready_datasets": MIN_READY_DATASETS,
        "reserve_ready": len(pool) >= MIN_READY_DATASETS,
        "sealed_hash_attestation_required_before_teacher": True,
        "failed_sealed_exam_reuses_dataset": False,
        "external_publication_reference_required": True,
    }
