"""Machine-verifiable task ledger for Jade Genesis 0.1.20.

The ledger stores bounded deterministic task cases, separates visible
TRAIN/VALIDATION evidence from a hidden SEALED_TEST partition, commits the
hidden set before evaluation, and scores exact JSON outputs internally.

0.1.20 hardens the final sealed evaluation:
- individual SEALED_TEST cases cannot be queried through evaluate_case();
- the whole sealed set can be consumed only by run_sealed_skill_exam();
- one sealed dataset is burned by the first final candidate;
- retries of the exact same frozen spec are idempotent;
- the public final result exposes only aggregate PASS/FAIL, never per-case
  expected values, per-case verdicts or the private nonce.

The ledger itself performs no LLM calls and is not conversation memory.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import secrets
import threading
import time
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
VERIFIER_KIND = "exact_json_v1"
PARTITIONS = {"TRAIN", "VALIDATION", "SEALED_TEST"}
MAX_DATASETS = 32
MAX_CASES_PER_DATASET = 500
MAX_ATTEMPTS = 2_000
MAX_JSON_BYTES = 16_384
MAX_JSON_DEPTH = 12
MAX_COLLECTION_ITEMS = 256
MAX_STRING_CHARS = 4_096
MAX_ID_CHARS = 160
FORBIDDEN_TASK_KEYS = {
    "raw_conversation_text",
    "conversation_text",
    "conversation",
    "messages",
    "system_prompt",
    "chat_history",
}
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

CONFIG_DIR = Path(
    os.environ.get("JADE_GENESIS_CONFIG_DIR", str(Path.home() / ".jade-genesis"))
)
TASK_LEDGER_PATH = CONFIG_DIR / "verifiable-task-ledger.json"


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _clean_id(value: Any, name: str) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:MAX_ID_CHARS]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _normalize_json(value: Any, depth: int = 0) -> Any:
    if depth > MAX_JSON_DEPTH:
        raise ValueError("task_json_too_deep")
    if value is None or isinstance(value, bool) or isinstance(value, int):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("task_json_non_finite_number")
        return value
    if isinstance(value, str):
        if len(value) > MAX_STRING_CHARS:
            raise ValueError("task_json_string_too_large")
        return value
    if isinstance(value, list):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise ValueError("task_json_collection_too_large")
        return [_normalize_json(item, depth + 1) for item in value]
    if isinstance(value, dict):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise ValueError("task_json_collection_too_large")
        result: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("task_json_key_must_be_string")
            clean_key = key.strip()
            if not clean_key:
                raise ValueError("task_json_empty_key")
            if clean_key.lower() in FORBIDDEN_TASK_KEYS:
                raise ValueError("task_ledger_not_conversation_memory")
            result[clean_key] = _normalize_json(item, depth + 1)
        return result
    raise ValueError("task_json_unsupported_type")


def _canonical_json(value: Any) -> str:
    normalized = _normalize_json(value)
    encoded = json.dumps(
        normalized,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    if len(encoded.encode("utf-8")) > MAX_JSON_BYTES:
        raise ValueError("task_json_too_large")
    return encoded


def _sha256_json(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value).encode("utf-8")).hexdigest()


class VerifiableTaskLedger:
    """Atomic identity-bound store for objectively scoreable task cases."""

    def __init__(self, path: Path = TASK_LEDGER_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "datasets": {},
            "attempts": [],
            "updated_at": 0,
        }

    def _decode(self, path: Path) -> dict[str, Any] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA_VERSION:
            return None
        if not isinstance(raw.get("datasets", {}), dict):
            return None
        if not isinstance(raw.get("attempts", []), list):
            return None
        return raw

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return self._empty()
        primary = self._decode(self.path)
        if primary is not None:
            return primary
        backup = self._decode(self.backup_path)
        if backup is not None:
            try:
                self.path.write_bytes(self.backup_path.read_bytes())
            except OSError:
                pass
            return backup
        raise RuntimeError("verifiable_task_ledger_corrupt")

    def _save(self, state: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        encoded = json.dumps(
            state,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        temp = self.path.with_suffix(self.path.suffix + ".tmp")
        temp.write_text(encoded, encoding="utf-8")
        if self.path.exists() and self._decode(self.path) is not None:
            try:
                self.backup_path.write_bytes(self.path.read_bytes())
            except OSError:
                pass
        temp.replace(self.path)

    @staticmethod
    def _bind_identity(state: dict[str, Any], identity_id: str) -> None:
        requested = _clean_id(identity_id, "identity_id")
        bound = str(state.get("identity_id", "")).strip()[:MAX_ID_CHARS]
        if bound and bound != requested:
            raise ValueError("verifiable_task_ledger_identity_mismatch")
        if not bound:
            state["identity_id"] = requested

    def create_dataset(
        self,
        identity_id: str,
        dataset_id: str,
        task_family: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        dataset_id = _clean_id(dataset_id, "dataset_id")
        task_family = _clean_id(task_family, "task_family")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            datasets = state["datasets"]
            if dataset_id in datasets:
                raise ValueError("dataset_already_exists")
            if len(datasets) >= MAX_DATASETS:
                raise ValueError("dataset_limit_reached")
            dataset = {
                "dataset_id": dataset_id,
                "task_family": task_family,
                "verifier_kind": VERIFIER_KIND,
                "sealed": False,
                "sealed_set_sha256": "",
                "seal_nonce": "",
                "created_at": now,
                "sealed_at": 0,
                "sealed_exam": None,
                "cases": {},
            }
            datasets[dataset_id] = dataset
            self._touch_and_save(state, now)
            return self._dataset_summary(dataset)

    def add_case(
        self,
        identity_id: str,
        dataset_id: str,
        case_id: str,
        partition: str,
        input_value: Any,
        expected_output: Any,
        source: str = "synthetic_or_curated",
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        dataset_id = _clean_id(dataset_id, "dataset_id")
        case_id = _clean_id(case_id, "case_id")
        partition = str(partition or "").strip().upper()
        if partition not in PARTITIONS:
            raise ValueError("invalid_task_partition")
        normalized_input = json.loads(_canonical_json(input_value))
        normalized_expected = json.loads(_canonical_json(expected_output))
        source = _clean_id(source, "source")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))

        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            dataset = state["datasets"].get(dataset_id)
            if not isinstance(dataset, dict):
                raise ValueError("dataset_not_found")
            if dataset.get("sealed") is True:
                raise PermissionError("sealed_dataset_is_immutable")
            cases = dataset["cases"]
            if case_id in cases:
                raise ValueError("task_case_already_exists")
            if len(cases) >= MAX_CASES_PER_DATASET:
                raise ValueError("task_case_limit_reached")
            cases[case_id] = {
                "case_id": case_id,
                "partition": partition,
                "input": normalized_input,
                "expected_output": normalized_expected,
                "source": source,
                "created_at": now,
            }
            self._touch_and_save(state, now)
            response = {
                "case_id": case_id,
                "partition": partition,
                "hidden": partition == "SEALED_TEST",
            }
            if partition != "SEALED_TEST":
                response["input_sha256"] = _sha256_json(normalized_input)
                response["expected_output_sha256"] = _sha256_json(normalized_expected)
            return response

    def seal_dataset(
        self,
        identity_id: str,
        dataset_id: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        dataset_id = _clean_id(dataset_id, "dataset_id")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            dataset = state["datasets"].get(dataset_id)
            if not isinstance(dataset, dict):
                raise ValueError("dataset_not_found")
            if dataset.get("sealed") is True:
                return self._sealed_manifest(dataset)
            hidden = [
                case
                for case in dataset["cases"].values()
                if isinstance(case, dict) and case.get("partition") == "SEALED_TEST"
            ]
            if not hidden:
                raise ValueError("sealed_test_partition_required")
            hidden.sort(key=lambda item: str(item.get("case_id", "")))
            nonce = secrets.token_hex(32)
            commitment = {
                "schema_version": SCHEMA_VERSION,
                "nonce": nonce,
                "dataset_id": dataset_id,
                "task_family": dataset.get("task_family", ""),
                "verifier_kind": VERIFIER_KIND,
                "sealed_cases": [
                    {
                        "case_id": case["case_id"],
                        "input": case["input"],
                        "expected_output": case["expected_output"],
                    }
                    for case in hidden
                ],
            }
            dataset["seal_nonce"] = nonce
            dataset["sealed_set_sha256"] = _sha256_json(commitment)
            dataset["sealed"] = True
            dataset["sealed_at"] = now
            dataset.setdefault("sealed_exam", None)
            self._touch_and_save(state, now)
            return self._sealed_manifest(dataset)

    def learning_view(self, identity_id: str, dataset_id: str) -> dict[str, Any]:
        """Expose teachable examples but never SEALED_TEST cases or the nonce."""

        dataset_id = _clean_id(dataset_id, "dataset_id")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            dataset = state["datasets"].get(dataset_id)
            if not isinstance(dataset, dict):
                raise ValueError("dataset_not_found")
            visible = [
                {
                    "case_id": case["case_id"],
                    "partition": case["partition"],
                    "input": case["input"],
                    "expected_output": case["expected_output"],
                }
                for case in dataset["cases"].values()
                if isinstance(case, dict)
                and case.get("partition") in {"TRAIN", "VALIDATION"}
            ]
            visible.sort(key=lambda item: (item["partition"], item["case_id"]))
            return {
                "dataset_id": dataset_id,
                "task_family": dataset.get("task_family", ""),
                "verifier_kind": VERIFIER_KIND,
                "cases": visible,
                "sealed_test_count": self._partition_count(dataset, "SEALED_TEST"),
                "sealed_set_sha256": dataset.get("sealed_set_sha256", ""),
                "sealed_exam_consumed": isinstance(dataset.get("sealed_exam"), dict),
                "sealed_test_inputs_exposed": False,
                "sealed_test_answers_exposed": False,
                "seal_nonce_exposed": False,
            }

    def sealed_manifest(self, identity_id: str, dataset_id: str) -> dict[str, Any]:
        dataset_id = _clean_id(dataset_id, "dataset_id")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            dataset = state["datasets"].get(dataset_id)
            if not isinstance(dataset, dict):
                raise ValueError("dataset_not_found")
            return self._sealed_manifest(dataset)

    def evaluate_case(
        self,
        identity_id: str,
        dataset_id: str,
        case_id: str,
        actual_output: Any,
        producer_kind: str,
        producer_id: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Evaluate TRAIN/VALIDATION only.

        SEALED_TEST is intentionally unavailable through this interactive API so
        its verdict cannot become an iterative training oracle.
        """

        dataset_id = _clean_id(dataset_id, "dataset_id")
        case_id = _clean_id(case_id, "case_id")
        producer_kind = _clean_id(producer_kind, "producer_kind")
        producer_id = _clean_id(producer_id, "producer_id")
        actual = json.loads(_canonical_json(actual_output))
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            dataset = state["datasets"].get(dataset_id)
            if not isinstance(dataset, dict):
                raise ValueError("dataset_not_found")
            case = dataset["cases"].get(case_id)
            if not isinstance(case, dict):
                raise ValueError("task_case_not_found")
            if case.get("partition") == "SEALED_TEST":
                raise PermissionError("sealed_test_case_evaluation_forbidden")
            if dataset.get("verifier_kind") != VERIFIER_KIND:
                raise ValueError("unsupported_verifier_kind")

            verdict = _canonical_json(actual) == _canonical_json(case["expected_output"])
            actual_sha = _sha256_json(actual)
            attempt_id = hashlib.sha256(
                f"{dataset_id}|{case_id}|{producer_kind}|{producer_id}|{now}|{actual_sha}".encode(
                    "utf-8"
                )
            ).hexdigest()[:24]
            attempts = [item for item in state["attempts"] if isinstance(item, dict)]
            attempts.append(
                {
                    "attempt_id": attempt_id,
                    "dataset_id": dataset_id,
                    "case_id": case_id,
                    "partition": case.get("partition", ""),
                    "producer_kind": producer_kind,
                    "producer_id": producer_id,
                    "actual_output_sha256": actual_sha,
                    "verdict": bool(verdict),
                    "evaluated_at": now,
                }
            )
            state["attempts"] = attempts[-MAX_ATTEMPTS:]
            self._touch_and_save(state, now)
            return {
                "attempt_id": attempt_id,
                "dataset_id": dataset_id,
                "case_id": case_id,
                "partition": case.get("partition", ""),
                "verifier_kind": VERIFIER_KIND,
                "verdict": bool(verdict),
                "actual_output_sha256": actual_sha,
                "expected_output_exposed": False,
                "seal_nonce_exposed": False,
            }

    def run_sealed_skill_exam(
        self,
        identity_id: str,
        dataset_id: str,
        raw_skill_spec: dict[str, Any],
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Consume one sealed dataset with one frozen developer SkillSpec.

        The verifier owns the expected outputs and calls the restricted
        procedure runtime internally. A different candidate can never reuse an
        already-consumed sealed dataset. Repeating the exact same candidate is
        idempotent and returns the stored aggregate verdict without re-running
        the cases.
        """

        from procedure_runtime import execute_skill
        from skill_spec import normalize_skill_spec

        dataset_id = _clean_id(dataset_id, "dataset_id")
        normalized_spec = normalize_skill_spec(raw_skill_spec)
        candidate_sha = str(normalized_spec.get("spec_sha256", "")).lower()
        if not _SHA256_RE.fullmatch(candidate_sha):
            raise ValueError("invalid_candidate_spec_sha256")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))

        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            dataset = state["datasets"].get(dataset_id)
            if not isinstance(dataset, dict):
                raise ValueError("dataset_not_found")
            if dataset.get("sealed") is not True:
                raise PermissionError("sealed_test_must_be_committed_before_final_exam")
            if dataset.get("verifier_kind") != VERIFIER_KIND:
                raise ValueError("unsupported_verifier_kind")

            expected_commitment = str(dataset.get("sealed_set_sha256", "")).lower()
            policy_commitment = str(
                normalized_spec["evaluation_policy"].get("sealed_set_sha256", "")
            ).lower()
            if not expected_commitment or policy_commitment != expected_commitment:
                raise ValueError("skill_sealed_set_mismatch")

            existing = dataset.get("sealed_exam")
            if isinstance(existing, dict):
                existing_sha = str(existing.get("candidate_spec_sha256", "")).lower()
                if existing_sha != candidate_sha:
                    raise PermissionError("sealed_dataset_exam_already_consumed")
                return self._sealed_exam_public(existing, replayed=True)

            hidden = [
                case
                for case in dataset["cases"].values()
                if isinstance(case, dict) and case.get("partition") == "SEALED_TEST"
            ]
            hidden.sort(key=lambda item: str(item.get("case_id", "")))
            if not hidden:
                raise ValueError("sealed_test_partition_required")

            passed_count = 0
            execution_failures = 0
            for case in hidden:
                try:
                    execution = execute_skill(normalized_spec, case["input"])
                    actual = execution["result"]
                    passed = _canonical_json(actual) == _canonical_json(
                        case["expected_output"]
                    )
                except Exception:
                    passed = False
                    execution_failures += 1
                if passed:
                    passed_count += 1

            total = len(hidden)
            pass_rate = passed_count / total
            min_pass_rate = float(
                normalized_spec["evaluation_policy"].get("min_pass_rate", 1.0)
            )
            verdict = pass_rate >= min_pass_rate

            exam_id = hashlib.sha256(
                (
                    f"{dataset_id}|{expected_commitment}|{candidate_sha}|"
                    f"{dataset.get('sealed_at', 0)}"
                ).encode("utf-8")
            ).hexdigest()[:24]

            internal = {
                "exam_id": exam_id,
                "candidate_spec_sha256": candidate_sha,
                "sealed_set_sha256": expected_commitment,
                "verdict": bool(verdict),
                "passed_count": passed_count,
                "total_count": total,
                "execution_failures": execution_failures,
                "evaluated_at": now,
                "feedback_detail_exposed": False,
                "per_case_verdicts_exposed": False,
                "expected_outputs_exposed": False,
            }
            dataset["sealed_exam"] = internal
            attempts = [item for item in state["attempts"] if isinstance(item, dict)]
            attempts.append(
                {
                    "attempt_id": exam_id,
                    "dataset_id": dataset_id,
                    "partition": "SEALED_TEST",
                    "producer_kind": "frozen_skill_spec",
                    "producer_id": candidate_sha,
                    "verdict": bool(verdict),
                    "evaluated_at": now,
                    "sealed_final_exam": True,
                }
            )
            state["attempts"] = attempts[-MAX_ATTEMPTS:]
            self._touch_and_save(state, now)
            return self._sealed_exam_public(internal, replayed=False)

    @staticmethod
    def _sealed_exam_public(
        exam: dict[str, Any],
        *,
        replayed: bool,
    ) -> dict[str, Any]:
        return {
            "exam_id": exam.get("exam_id", ""),
            "candidate_spec_sha256": exam.get("candidate_spec_sha256", ""),
            "sealed_set_sha256": exam.get("sealed_set_sha256", ""),
            "verdict": bool(exam.get("verdict", False)),
            "replayed": bool(replayed),
            "dataset_consumed": True,
            "feedback_detail_exposed": False,
            "per_case_verdicts_exposed": False,
            "expected_outputs_exposed": False,
            "seal_nonce_exposed": False,
        }

    def _touch_and_save(self, state: dict[str, Any], now: int) -> None:
        state["revision"] = max(0, int(state.get("revision", 0))) + 1
        state["updated_at"] = now
        self._save(state)

    @staticmethod
    def _partition_count(dataset: dict[str, Any], partition: str) -> int:
        return sum(
            1
            for case in dataset.get("cases", {}).values()
            if isinstance(case, dict) and case.get("partition") == partition
        )

    @classmethod
    def _dataset_summary(cls, dataset: dict[str, Any]) -> dict[str, Any]:
        return {
            "dataset_id": dataset.get("dataset_id", ""),
            "task_family": dataset.get("task_family", ""),
            "verifier_kind": VERIFIER_KIND,
            "sealed": bool(dataset.get("sealed", False)),
            "case_count": len(dataset.get("cases", {})),
            "train_count": cls._partition_count(dataset, "TRAIN"),
            "validation_count": cls._partition_count(dataset, "VALIDATION"),
            "sealed_test_count": cls._partition_count(dataset, "SEALED_TEST"),
            "sealed_set_sha256": dataset.get("sealed_set_sha256", ""),
            "sealed_exam_consumed": isinstance(dataset.get("sealed_exam"), dict),
            "sealed_test_inputs_exposed": False,
            "sealed_test_answers_exposed": False,
            "seal_nonce_exposed": False,
        }

    @classmethod
    def _sealed_manifest(cls, dataset: dict[str, Any]) -> dict[str, Any]:
        return {
            "dataset_id": dataset.get("dataset_id", ""),
            "task_family": dataset.get("task_family", ""),
            "verifier_kind": VERIFIER_KIND,
            "sealed": bool(dataset.get("sealed", False)),
            "sealed_at": max(0, int(dataset.get("sealed_at", 0))),
            "sealed_test_count": cls._partition_count(dataset, "SEALED_TEST"),
            "sealed_set_sha256": dataset.get("sealed_set_sha256", ""),
            "sealed_exam_consumed": isinstance(dataset.get("sealed_exam"), dict),
            "sealed_test_inputs_exposed": False,
            "sealed_test_answers_exposed": False,
            "seal_nonce_exposed": False,
        }

    def status(self) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            datasets = [
                item for item in state["datasets"].values() if isinstance(item, dict)
            ]
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "revision": max(0, int(state.get("revision", 0))),
                "dataset_count": len(datasets),
                "sealed_dataset_count": sum(
                    1 for item in datasets if item.get("sealed") is True
                ),
                "sealed_exam_consumed_count": sum(
                    1 for item in datasets if isinstance(item.get("sealed_exam"), dict)
                ),
                "case_count": sum(len(item.get("cases", {})) for item in datasets),
                "attempt_count": len(
                    [item for item in state["attempts"] if isinstance(item, dict)]
                ),
                "verifier_kind": VERIFIER_KIND,
                "sealed_commitment_salted": True,
                "sealed_test_inputs_exposed": False,
                "sealed_test_answers_exposed": False,
                "seal_nonce_exposed": False,
                "sealed_case_evaluation_allowed": False,
                "sealed_final_exam_single_use": True,
                "sealed_final_exam_feedback": "aggregate_pass_fail_only",
                "learned_code_execution": False,
                "llm_judge_used": False,
                "network_access": False,
                "conversation_memory": False,
                "updated_at": max(0, int(state.get("updated_at", 0))),
            }


_GLOBAL_LEDGER = VerifiableTaskLedger()


def verifiable_task_ledger_status() -> dict[str, Any]:
    try:
        return _GLOBAL_LEDGER.status()
    except RuntimeError:
        return {
            "schema_version": SCHEMA_VERSION,
            "healthy": False,
            "error": "verifiable_task_ledger_corrupt",
            "sealed_commitment_salted": True,
            "sealed_test_inputs_exposed": False,
            "sealed_test_answers_exposed": False,
            "seal_nonce_exposed": False,
            "sealed_case_evaluation_allowed": False,
            "sealed_final_exam_single_use": True,
            "sealed_final_exam_feedback": "aggregate_pass_fail_only",
            "learned_code_execution": False,
            "llm_judge_used": False,
            "network_access": False,
            "conversation_memory": False,
        }
