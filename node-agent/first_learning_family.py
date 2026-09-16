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

from learning_environment import append_attribution_event_once
from learning_stores import open_archive_ledger

TASK_FAMILY = "normalize_label_v1"
CASES_PER_DATASET = 5
PARTITION_PLAN = ("TRAIN", "TRAIN", "VALIDATION", "SEALED_TEST", "SEALED_TEST")
PARTITION_COUNTS = {
    "TRAIN": 2,
    "VALIDATION": 1,
    "SEALED_TEST": 2,
}

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
        datasets.sort(
            key=lambda item: (
                int(item.get("created_at", 0)),
                str(item.get("dataset_id", "")),
            )
        )
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


def _sealed_event_payload(
    identity_id: str,
    dataset_id: str,
    manifest: dict[str, Any],
    *,
    recovered_after_interruption: bool,
) -> dict[str, Any]:
    return {
        "identity_id": identity_id,
        "task_family": TASK_FAMILY,
        "dataset_id": dataset_id,
        "sealed_set_sha256": manifest["sealed_set_sha256"],
        "case_count": CASES_PER_DATASET,
        "partition_plan": list(PARTITION_PLAN),
        "partition_counts": dict(PARTITION_COUNTS),
        "partition_plan_fixed_before_teacher": True,
        "real_production_traffic": True,
        "external_attestation_required": True,
        "recovered_after_interruption": recovered_after_interruption,
    }


def _append_sealed_event_once(
    payload: dict[str, Any],
    *,
    now_ms: int | None,
) -> dict[str, Any]:
    return append_attribution_event_once(
        "real_dataset_sealed",
        payload,
        key_fields=("identity_id", "task_family", "dataset_id"),
        verify_fields=(
            "sealed_set_sha256",
            "case_count",
            "partition_plan",
            "partition_counts",
        ),
        now_ms=now_ms,
    )


def _validate_completed_dataset(dataset: dict[str, Any]) -> str:
    """Fail closed unless one five-case dataset exactly matches this family contract."""

    dataset_id = str(dataset.get("dataset_id", ""))
    cases = dataset.get("cases", {})
    if not dataset_id or not isinstance(cases, dict):
        raise RuntimeError("normalize_label_recovery_dataset_invalid")
    if len(cases) != CASES_PER_DATASET:
        raise RuntimeError("normalize_label_recovery_case_count_invalid")

    expected_ids = {f"real-{position + 1:02d}" for position in range(CASES_PER_DATASET)}
    if set(cases) != expected_ids:
        raise RuntimeError("normalize_label_recovery_case_ids_invalid")

    for position, partition in enumerate(PARTITION_PLAN):
        case_id = f"real-{position + 1:02d}"
        case = cases.get(case_id)
        if not isinstance(case, dict):
            raise RuntimeError("normalize_label_recovery_case_invalid")
        if case.get("partition") != partition:
            raise RuntimeError("normalize_label_recovery_partition_invalid")
        if case.get("source") != "real_production_brain_chat":
            raise RuntimeError("normalize_label_recovery_source_invalid")
        try:
            expected = expected_output(case.get("input"))
        except ValueError as exc:
            raise RuntimeError("normalize_label_recovery_input_invalid") from exc
        if _canonical(case.get("expected_output")) != _canonical(expected):
            raise RuntimeError("normalize_label_recovery_expected_output_invalid")
    return dataset_id


def _recover_completed_unsealed_datasets(
    ledger: Any,
    identity_id: str,
    datasets: list[dict[str, Any]],
    *,
    now_ms: int | None,
) -> list[dict[str, Any]]:
    """Seal datasets whose fifth case was durable but whose seal write was interrupted."""

    events: list[dict[str, Any]] = []
    for dataset in datasets:
        if dataset.get("sealed") is True:
            continue
        cases = dataset.get("cases", {})
        if not isinstance(cases, dict):
            raise RuntimeError("normalize_label_dataset_cases_invalid")
        if len(cases) < CASES_PER_DATASET:
            continue
        if len(cases) > CASES_PER_DATASET:
            raise RuntimeError("normalize_label_dataset_overfilled")
        dataset_id = _validate_completed_dataset(dataset)
        manifest = ledger.seal_dataset(identity_id, dataset_id, now_ms=now_ms)
        events.append(
            _sealed_event_payload(
                identity_id,
                dataset_id,
                manifest,
                recovered_after_interruption=True,
            )
        )
    return events


def _reconcile_completed_sealed_datasets(
    ledger: Any,
    identity_id: str,
    datasets: list[dict[str, Any]],
    *,
    now_ms: int | None,
) -> list[dict[str, Any]]:
    """Re-materialize/verify case packs and repair missing seal attribution.

    ``ArchiveVerifiableTaskLedger.seal_dataset`` is idempotent for an already
    sealed dataset and always verifies/materializes its immutable case pack. By
    returning an idempotent attribution payload for every valid sealed dataset,
    a later enrollment can finish work interrupted after the ledger seal but
    before case-pack publication or journal append.
    """

    events: list[dict[str, Any]] = []
    for dataset in datasets:
        if dataset.get("sealed") is not True:
            continue
        cases = dataset.get("cases", {})
        if not isinstance(cases, dict):
            raise RuntimeError("normalize_label_dataset_cases_invalid")
        if len(cases) != CASES_PER_DATASET:
            raise RuntimeError("normalize_label_recovery_case_count_invalid")
        dataset_id = _validate_completed_dataset(dataset)
        manifest = ledger.seal_dataset(identity_id, dataset_id, now_ms=now_ms)
        events.append(
            _sealed_event_payload(
                identity_id,
                dataset_id,
                manifest,
                recovered_after_interruption=True,
            )
        )
    return events


def _public_recovered_receipts(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "dataset_id": event["dataset_id"],
            "sealed_set_sha256": event["sealed_set_sha256"],
            "case_count": event["case_count"],
            "partition_plan": list(event["partition_plan"]),
            "partition_counts": dict(event["partition_counts"]),
            "external_attestation_required": True,
        }
        for event in events
    ]


def record_real_case(
    identity_id: str,
    input_value: Any,
    *,
    now_ms: int | None = None,
) -> dict[str, Any]:
    """Crash-recoverably enroll one unique real input in the fixed partition stream.

    Before a new enrollment, complete datasets are reconciled across the ledger,
    immutable case-pack archive and attribution journal. This closes the crash
    windows after the fifth case, after the ledger seal and after case-pack
    publication while preserving the fixed hidden partition and existing hashes.
    """

    expected = expected_output(input_value)
    ledger = open_archive_ledger()
    sealed_event_payloads: list[dict[str, Any]] = []

    with ledger.lock:
        datasets = _family_datasets(ledger, identity_id)
        sealed_event_payloads.extend(
            _reconcile_completed_sealed_datasets(
                ledger,
                identity_id,
                datasets,
                now_ms=now_ms,
            )
        )
        recovered_seal_payloads = _recover_completed_unsealed_datasets(
            ledger,
            identity_id,
            datasets,
            now_ms=now_ms,
        )
        sealed_event_payloads.extend(recovered_seal_payloads)
        if recovered_seal_payloads:
            datasets = _family_datasets(ledger, identity_id)
        recovered_receipts = _public_recovered_receipts(recovered_seal_payloads)

        if _input_already_used(datasets, input_value):
            result: dict[str, Any] = {
                "recorded": False,
                "reason": "duplicate_real_input",
                "task_family": TASK_FAMILY,
            }
            if recovered_receipts:
                result["recovered_dataset_seals"] = recovered_receipts
        else:
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
            partition = PARTITION_PLAN[position]
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
            result = {
                "recorded": True,
                "task_family": TASK_FAMILY,
                "dataset_id": dataset_id,
                "case_id": case_id,
                "partition": partition,
                "dataset_sealed": False,
                "real_production_traffic": True,
            }
            if recovered_receipts:
                result["recovered_dataset_seals"] = recovered_receipts

            if position + 1 == CASES_PER_DATASET:
                manifest = ledger.seal_dataset(identity_id, dataset_id, now_ms=now_ms)
                result.update(
                    {
                        "dataset_sealed": True,
                        "sealed_set_sha256": manifest["sealed_set_sha256"],
                        "sealed_test_count": manifest["sealed_test_count"],
                        "case_count": CASES_PER_DATASET,
                        "partition_plan": list(PARTITION_PLAN),
                        "partition_counts": dict(PARTITION_COUNTS),
                        "external_attestation_required": True,
                    }
                )
                sealed_event_payloads.append(
                    _sealed_event_payload(
                        identity_id,
                        dataset_id,
                        manifest,
                        recovered_after_interruption=False,
                    )
                )

    # The ledger/case-pack work is durable before journal reconciliation. If the
    # process stops during this separate append, the next real enrollment scans
    # the sealed datasets again and idempotently finishes the missing event.
    for payload in sealed_event_payloads:
        _append_sealed_event_once(payload, now_ms=now_ms)
    return result


def family_status(identity_id: str) -> dict[str, Any]:
    ledger = open_archive_ledger()
    datasets = _family_datasets(ledger, identity_id)
    return {
        "task_family": TASK_FAMILY,
        "dataset_count": len(datasets),
        "sealed_dataset_count": sum(
            1 for item in datasets if item.get("sealed") is True
        ),
        "unconsumed_sealed_dataset_count": sum(
            1
            for item in datasets
            if item.get("sealed") is True
            and not isinstance(item.get("sealed_exam"), dict)
        ),
        "cases_per_dataset": CASES_PER_DATASET,
        "fixed_partition_plan": list(PARTITION_PLAN),
        "partition_counts": dict(PARTITION_COUNTS),
        "source_required": "real_production_brain_chat",
        "teacher_used_for_partitioning": False,
    }
