"""Launch protocol for the first real Jade Genesis 0.1.21 acquisition.

This module does not add another learning engine. It is the gate around the
existing SkillSynthesisLoop used by production/night-cycle plumbing.

Before a teacher may run:
- the dataset is already sealed;
- the exact sealed_set_sha256 plus dataset structure has been attested with a
  reference outside the learning workshop (operator/GitHub/runbook receipt);
- the family owns a reserve pool of multiple sealed, attested, unconsumed
  datasets so one failed one-shot exam never motivates unsealing/reuse.

Failure policy is precommitted before the live trial:
- one SEALED_TEST failure ends the 0.1.21 trial for that family;
- the failed frozen candidate may not be resubmitted on another fresh dataset;
- the remaining reserve datasets are not retry coupons;
- a later attempt requires a new explicit protocol revision, not an automatic
  retry after observing hidden feedback.

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
TRIAL_PROTOCOL_ID = "0.1.21-first-acquisition-v1"
MAX_SEALED_FAILURES_PER_TRIAL = 1
FAILED_CANDIDATE_MAY_RETRY_FRESH_DATASET = False
FAMILY_TERMINAL_AFTER_SEALED_FAILURE = True

# The first acquisition demo is deliberately not a mastery claim. For the
# normalize_label_v1 family each sealed dataset contains two hidden cases. After
# the live acquisition demo, four additional fresh sealed datasets are required
# for a stronger same-frozen-spec confirmation (8 fresh hidden cases, 10 hidden
# cases total including the acquisition exam). This is a robustness threshold,
# not a proof of statistical independence.
POST_DEMO_CONFIRMATORY_FRESH_DATASETS = 4
POST_DEMO_CONFIRMATORY_FRESH_HIDDEN_CASES = 8
POST_DEMO_TOTAL_HIDDEN_CASE_TARGET = 10


def _clean(value: Any, name: str, limit: int = 240) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:limit]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _normalized_partition_plan(raw: Any) -> list[str]:
    if not isinstance(raw, (list, tuple)) or not raw:
        raise ValueError("invalid_partition_plan")
    result = []
    for item in raw:
        partition = str(item or "").strip().upper()
        if partition not in {"TRAIN", "VALIDATION", "SEALED_TEST"}:
            raise ValueError("invalid_partition_plan")
        result.append(partition)
    return result


def _dataset_summaries(identity_id: str, task_family: str) -> list[dict[str, Any]]:
    """Read bounded summaries from the canonical archive ledger.

    This is deliberately inside the verifier process. It returns no case input,
    expected output or seal nonce. The public structure includes only case count
    and partition disposition so the external precommitment locks the split as
    well as the hidden-content commitment.
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
            cases = [
                case
                for case in dataset.get("cases", {}).values()
                if isinstance(case, dict)
            ]
            cases.sort(key=lambda case: str(case.get("case_id", "")))
            partition_plan = [
                str(case.get("partition", "")).strip().upper()
                for case in cases
            ]
            partition_counts = {
                "TRAIN": partition_plan.count("TRAIN"),
                "VALIDATION": partition_plan.count("VALIDATION"),
                "SEALED_TEST": partition_plan.count("SEALED_TEST"),
            }
            result.append(
                {
                    "dataset_id": str(dataset.get("dataset_id", "")),
                    "task_family": family,
                    "sealed": dataset.get("sealed") is True,
                    "sealed_at": max(0, int(dataset.get("sealed_at", 0))),
                    "sealed_set_sha256": str(dataset.get("sealed_set_sha256", "")),
                    "sealed_exam_consumed": isinstance(dataset.get("sealed_exam"), dict),
                    "sealed_test_count": ledger._partition_count(dataset, "SEALED_TEST"),
                    "case_count": len(cases),
                    "partition_plan": partition_plan,
                    "partition_counts": partition_counts,
                    "sealed_test_inputs_exposed": False,
                    "sealed_test_answers_exposed": False,
                }
            )
        result.sort(key=lambda item: (item["sealed_at"], item["dataset_id"]))
        return result


def _dataset_summary(identity_id: str, dataset_id: str, task_family: str) -> dict[str, Any]:
    dataset = _clean(dataset_id, "dataset_id", 160)
    for item in _dataset_summaries(identity_id, task_family):
        if item["dataset_id"] == dataset:
            return item
    raise ValueError("dataset_not_found")


def _terminal_failure_for_family(
    identity_id: str,
    task_family: str,
) -> dict[str, Any] | None:
    identity = _clean(identity_id, "identity_id", 160)
    family = _clean(task_family, "task_family", 160)
    for event in read_attribution_events(path=ARCHIVE_ATTRIBUTION_LOG_PATH):
        if event.get("event_kind") != "learning_trial_terminal_failure":
            continue
        payload = event.get("payload", {})
        if not isinstance(payload, dict):
            continue
        if (
            str(payload.get("protocol_id", "")) == TRIAL_PROTOCOL_ID
            and str(payload.get("identity_id", "")) == identity
            and str(payload.get("task_family", "")) == family
        ):
            return {
                "occurred_at": max(0, int(event.get("occurred_at", 0))),
                "event_sha256": str(event.get("event_sha256", "")),
                "goal_id": str(payload.get("goal_id", "")),
                "dataset_id": str(payload.get("dataset_id", "")),
                "candidate_spec_sha256": str(
                    payload.get("candidate_spec_sha256", "")
                ),
            }
    return None


def publish_seal_attestation(
    identity_id: str,
    dataset_id: str,
    sealed_set_sha256: str,
    *,
    case_count: int,
    partition_plan: list[str] | tuple[str, ...],
    external_publication_ref: str,
    now_ms: int | None = None,
) -> dict[str, Any]:
    """Record externally published seal + split metadata before learning starts.

    For the formal real trial, the external evidence must publish all of:
    dataset_id, sealed_set_sha256, case_count and partition_plan. This prevents a
    hash-only receipt from leaving the partition disposition implicit.
    """

    identity = _clean(identity_id, "identity_id", 160)
    dataset = _clean(dataset_id, "dataset_id", 160)
    supplied_hash = str(sealed_set_sha256 or "").strip().lower()
    supplied_plan = _normalized_partition_plan(partition_plan)
    try:
        supplied_case_count = int(case_count)
    except (TypeError, ValueError):
        raise ValueError("invalid_case_count") from None
    if supplied_case_count < 1:
        raise ValueError("invalid_case_count")
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

    summary = _dataset_summary(identity, dataset, str(manifest.get("task_family", "")))
    if supplied_case_count != int(summary["case_count"]):
        raise ValueError("seal_attestation_case_count_mismatch")
    if supplied_plan != summary["partition_plan"]:
        raise ValueError("seal_attestation_partition_plan_mismatch")

    event = append_attribution_event(
        "sealed_dataset_attested",
        {
            "protocol_id": TRIAL_PROTOCOL_ID,
            "identity_id": identity,
            "dataset_id": dataset,
            "task_family": manifest.get("task_family", ""),
            "sealed_set_sha256": expected,
            "sealed_at": manifest.get("sealed_at", 0),
            "case_count": supplied_case_count,
            "partition_plan": supplied_plan,
            "partition_counts": summary["partition_counts"],
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
        "protocol_id": TRIAL_PROTOCOL_ID,
        "dataset_id": dataset,
        "sealed_set_sha256": expected,
        "case_count": supplied_case_count,
        "partition_plan": supplied_plan,
        "partition_counts": summary["partition_counts"],
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
            str(payload.get("protocol_id", "")) == TRIAL_PROTOCOL_ID
            and str(payload.get("identity_id", "")) == identity
            and str(payload.get("dataset_id", "")) == dataset
            and str(payload.get("sealed_set_sha256", "")).lower() == expected_hash
        ):
            raw_plan = payload.get("partition_plan", [])
            try:
                plan = _normalized_partition_plan(raw_plan)
                count = int(payload.get("case_count", 0))
            except (ValueError, TypeError):
                return None
            return {
                "attested_at": int(event.get("occurred_at", 0)),
                "external_publication_ref": str(
                    payload.get("external_publication_ref", "")
                ),
                "attribution_event_sha256": str(event.get("event_sha256", "")),
                "case_count": count,
                "partition_plan": plan,
                "partition_counts": payload.get("partition_counts", {}),
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
        if int(attestation.get("case_count", 0)) != int(item["case_count"]):
            continue
        if attestation.get("partition_plan") != item["partition_plan"]:
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
    if _terminal_failure_for_family(identity_id, task_family) is not None:
        raise PermissionError("learning_trial_family_terminal_after_sealed_failure")
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
    if _terminal_failure_for_family(identity_id, goal["task_family"]) is not None:
        raise PermissionError("learning_trial_family_terminal_after_sealed_failure")

    ledger = open_archive_ledger()
    manifest = ledger.sealed_manifest(identity_id, goal["dataset_id"])
    attestation = attestation_for_dataset(
        identity_id,
        goal["dataset_id"],
        manifest.get("sealed_set_sha256", ""),
    )
    if attestation is None:
        raise PermissionError("teacher_requires_preexisting_structured_seal_attestation")
    if int(attestation["attested_at"]) < int(manifest.get("sealed_at", 0)):
        raise PermissionError("teacher_requires_post_seal_attestation")

    summary = _dataset_summary(
        identity_id,
        goal["dataset_id"],
        goal["task_family"],
    )
    if int(attestation.get("case_count", 0)) != int(summary["case_count"]):
        raise PermissionError("teacher_requires_matching_attested_case_count")
    if attestation.get("partition_plan") != summary["partition_plan"]:
        raise PermissionError("teacher_requires_matching_attested_partition_plan")

    start_event = append_attribution_event(
        "skill_teacher_started",
        {
            "protocol_id": TRIAL_PROTOCOL_ID,
            "identity_id": _clean(identity_id, "identity_id", 160),
            "goal_id": _clean(goal_id, "goal_id", 160),
            "dataset_id": goal["dataset_id"],
            "task_family": goal["task_family"],
            "sealed_set_sha256": manifest.get("sealed_set_sha256", ""),
            "attested_case_count": attestation["case_count"],
            "attested_partition_plan": attestation["partition_plan"],
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

    trial_terminal = False
    terminal_event_sha256 = ""
    if result.get("state") == "SEALED_FAILED":
        terminal_event = append_attribution_event(
            "learning_trial_terminal_failure",
            {
                "protocol_id": TRIAL_PROTOCOL_ID,
                "identity_id": _clean(identity_id, "identity_id", 160),
                "goal_id": _clean(goal_id, "goal_id", 160),
                "dataset_id": goal["dataset_id"],
                "task_family": goal["task_family"],
                "candidate_spec_sha256": result.get("selected_spec_sha256", ""),
                "sealed_failures_used": 1,
                "max_sealed_failures_per_trial": MAX_SEALED_FAILURES_PER_TRIAL,
                "failed_candidate_retry_on_fresh_dataset_allowed": False,
                "remaining_reserve_is_not_retry_budget": True,
                "family_status_for_protocol": "NOT_LEARNED_TERMINAL",
            },
            path=ARCHIVE_ATTRIBUTION_LOG_PATH,
            now_ms=now_ms,
        )
        trial_terminal = True
        terminal_event_sha256 = str(terminal_event.get("event_sha256", ""))

    return {
        **result,
        "trial_protocol_id": TRIAL_PROTOCOL_ID,
        "seal_attestation_verified": True,
        "structured_partition_attestation_verified": True,
        "sealed_dataset_reserve_after_run": len(
            ready_dataset_pool(identity_id, goal["task_family"])
        ),
        "trial_terminal": trial_terminal,
        "terminal_failure_event_sha256": terminal_event_sha256,
        "max_sealed_failures_per_trial": MAX_SEALED_FAILURES_PER_TRIAL,
        "failed_candidate_retry_on_fresh_dataset_allowed": (
            FAILED_CANDIDATE_MAY_RETRY_FRESH_DATASET
        ),
        "initial_acquisition_is_mastery_claim": False,
        "post_demo_confirmatory_fresh_datasets_required": (
            POST_DEMO_CONFIRMATORY_FRESH_DATASETS
        ),
        "post_demo_total_hidden_case_target": POST_DEMO_TOTAL_HIDDEN_CASE_TARGET,
    }


def learning_trial_status(identity_id: str, task_family: str) -> dict[str, Any]:
    pool = ready_dataset_pool(identity_id, task_family)
    terminal = _terminal_failure_for_family(identity_id, task_family)
    return {
        "trial_protocol_id": TRIAL_PROTOCOL_ID,
        "task_family": task_family,
        "ready_attested_dataset_count": len(pool),
        "minimum_ready_datasets": MIN_READY_DATASETS,
        "reserve_ready": len(pool) >= MIN_READY_DATASETS,
        "sealed_hash_attestation_required_before_teacher": True,
        "attestation_requires_dataset_id": True,
        "attestation_requires_case_count": True,
        "attestation_requires_partition_plan": True,
        "failed_sealed_exam_reuses_dataset": False,
        "max_sealed_failures_per_trial": MAX_SEALED_FAILURES_PER_TRIAL,
        "failed_candidate_retry_on_fresh_dataset_allowed": (
            FAILED_CANDIDATE_MAY_RETRY_FRESH_DATASET
        ),
        "family_terminal_after_sealed_failure": FAMILY_TERMINAL_AFTER_SEALED_FAILURE,
        "terminal_failure_present": terminal is not None,
        "remaining_reserve_is_retry_budget": False,
        "external_publication_reference_required": True,
        "initial_acquisition_is_mastery_claim": False,
        "post_demo_confirmation_same_frozen_spec": True,
        "post_demo_confirmation_teacher_calls_allowed": False,
        "post_demo_confirmatory_fresh_datasets_required": (
            POST_DEMO_CONFIRMATORY_FRESH_DATASETS
        ),
        "post_demo_confirmatory_fresh_hidden_cases": (
            POST_DEMO_CONFIRMATORY_FRESH_HIDDEN_CASES
        ),
        "post_demo_total_hidden_case_target": POST_DEMO_TOTAL_HIDDEN_CASE_TARGET,
    }
