"""First real, intentionally boring learning family for Jade Genesis 0.1.21.

Family: normalize_label_v1
Input:  {"text": <string>}
Output: {"text": input.text.strip().lower()}

The oracle exists only to produce objectively correct ledger pairs while the
production request itself still falls back to the current brain until a Skill is
learned. Cases are sourced from real structured brain_chat traffic. Partition
positions are fixed before any teacher exists:
  1-2 TRAIN, 3 VALIDATION, 4-5 SEALED_TEST.
Each completed five-case dataset is sealed immediately. Three independently
sealed/attested datasets are required by learning_trial_protocol before teaching.
"""

from __future__ import annotations

import json
from typing import Any

from learning_environment import append_attribution_event
from learning_stores import open_archive_ledger

TASK_FAMILY = "normalize_label_v1"
CASES_PER_DATASET = 5
_PARTITIONS = ("TRAIN", "TRAIN", "VALIDATION", "SEALED_TEST", "SEALED_TEST")

INPUT_CONTRACT = {
    "type": "object",
    "required_fields": ["text"],
    "description": "One label string to normalize.",
}
OUTPUT_CONTRACT = {
    "type": "object",
    "required_fields": ["text"],
    "description": "The same label trimmed and lower-cased.",
}


def expected_output(input_value: Any) -> dict[str, str]:
    if not isinstance(input_value, dict):
        raise ValueError("normalize_label_input_must_be_object")
    text = input_value.get("text")
    if not isinstance(text, str):
        raise ValueError("normalize_label_text_must_be_string")
    if len(text) > 4_096:
        raise ValueError("normalize_label_text_too_large")
    return {"text": text.strip().lower()}


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _family_datasets(ledger: Any, identity_id: str) -> list[dict[str, Any]]:
    with ledger.lock:
        state = ledger._load()
        ledger._bind_identity(state, identity_id)
        datasets = [
            item
            for item in state.get("datasets", {}).values()
            if isinstance(item, dict) and item.get("task_family") == TASK_FAMILY
        ]
        datasets.sort(key=lambda item: (int(item.get("created_at", 0)), str(item.get("dataset_id", ""))))
        return json.loads(json.dumps(datasets))


def _input_already_used(datasets: list[dict[str, Any]], input_value: Any) -> bool:
    canonical = _canonical(input_value)
    for dataset in datasets:
        for case in dataset.get("cases", {}).values():
            if isinstance(case, dict) and _canonical(case.get("input")) == canonical:
                return True
    return False


def input_seen_in_learning(identity_id: str, input_value: Any) -> bool:
    ledger = open_archive_ledger()
    return _input_already_used(_family_datasets(ledger, identity_id), input_value)


def _next_dataset_id(datasets: list[dict[str, Any]]) -> str:
    highest = 0
    for dataset in datasets:
        dataset_id = str(dataset.get("dataset_id", ""))
        if not dataset_id.startswith("normalize-label-real-"):
            continue
        try:
            highest = max(highest, int(dataset_id.rsplit("-", 1)[-1]))
        except ValueError:
            continue
    return f"normalize-label-real-{highest + 1:04d}"


def record_real_case(
    identity_id: str,
    input_value: Any,
    *,
    now_ms: int | None = None,
) -> dict[str, Any]:
    """Add one unique real production input to the next pre-partitioned dataset."""

    expected = expected_output(input_value)
    ledger = open_archive_ledger()
    datasets = _family_datasets(ledger, identity_id)
    if _input_already_used(datasets, input_value):
        return {
            "recorded": False,
            "reason": "duplicate_real_input",
            "task_family": TASK_FAMILY,
        }

    current = next(
        (
            item
            for item in reversed(datasets)
            if item.get("sealed") is not True
            and len(item.get("cases", {})) < CASES_PER_DATASET
        ),
        None,
    )
    if current is None:
        dataset_id = _next_dataset_id(datasets)
        ledger.create_dataset(identity_id, dataset_id, TASK_FAMILY, now_ms=now_ms)
        position = 0
    else:
        dataset_id = str(current["dataset_id"])
        position = len(current.get("cases", {}))

    if position < 0 or position >= CASES_PER_DATASET:
        raise RuntimeError("normalize_label_dataset_position_invalid")
    partition = _PARTITIONS[position]
    case_id = f"real-{position + 1:02d}"
    ledger.add_case(
        identity_id,
        dataset_id,
        case_id,
        partition,
        input_value,
        expected,
        source="real_production_brain_chat",
        now_ms=now_ms,
    )
    result: dict[str, Any] = {
        "recorded": True,
        "task_family": TASK_FAMILY,
        "dataset_id": dataset_id,
        "case_id": case_id,
        "partition": partition,
        "dataset_sealed": False,
        "real_production_traffic": True,
    }

    if position + 1 == CASES_PER_DATASET:
        manifest = ledger.seal_dataset(identity_id, dataset_id, now_ms=now_ms)
        result.update(
            {
                "dataset_sealed": True,
                "sealed_set_sha256": manifest["sealed_set_sha256"],
                "sealed_test_count": manifest["sealed_test_count"],
                "external_attestation_required": True,
            }
        )
        append_attribution_event(
            "real_dataset_sealed",
            {
                "identity_id": identity_id,
                "task_family": TASK_FAMILY,
                "dataset_id": dataset_id,
                "sealed_set_sha256": manifest["sealed_set_sha256"],
                "case_count": CASES_PER_DATASET,
                "partition_plan_fixed_before_teacher": True,
                "real_production_traffic": True,
                "external_attestation_required": True,
            },
            now_ms=now_ms,
        )
    return result


def family_status(identity_id: str) -> dict[str, Any]:
    ledger = open_archive_ledger()
    datasets = _family_datasets(ledger, identity_id)
    return {
        "task_family": TASK_FAMILY,
        "dataset_count": len(datasets),
        "sealed_dataset_count": sum(1 for item in datasets if item.get("sealed") is True),
        "unconsumed_sealed_dataset_count": sum(
            1
            for item in datasets
            if item.get("sealed") is True and not isinstance(item.get("sealed_exam"), dict)
        ),
        "cases_per_dataset": CASES_PER_DATASET,
        "fixed_partition_plan": list(_PARTITIONS),
        "source_required": "real_production_brain_chat",
        "teacher_used_for_partitioning": False,
    }
