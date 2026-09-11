"""Bounded verified Skill Synthesis Loop for Jade Genesis 0.1.21.

This module is the first causal acquisition loop for executable procedures:

    missing exact-family skill -> LearningGoal -> external teacher proposal
    -> TRAIN/VALIDATION execution -> frozen SkillSpec -> one-shot SEALED_TEST
    -> verified retention/activation -> local reuse after restart

The teacher never owns the verifier policy, provenance, sealed-set commitment or
activation decision. It only proposes a restricted DSL body plus descriptive
metadata. Jade constructs the final SkillSpec itself.

SEALED_TEST cases and the private seal nonce are never included in teacher
requests. A sealed dataset is consumed by at most one frozen candidate. The
teacher may iterate only against the visible TRAIN/VALIDATION evidence.

No shell, filesystem, process, arbitrary Python, model-weight mutation or
Skill-to-Skill dependency is introduced here. Learned procedures remain bounded
by JADE_PROCEDURE_DSL_V1 and procedure_runtime budgets.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Any, Callable

from procedure_runtime import execute_skill
from skill_spec import ALLOWED_OPS, BODY_KIND, normalize_skill_spec

SCHEMA_VERSION = 1
MAX_GOALS = 128
MAX_CANDIDATES = 4
MAX_ID_CHARS = 160
MAX_REASON_CHARS = 800
CONFIG_DIR = Path(
    os.environ.get("JADE_GENESIS_CONFIG_DIR", str(Path.home() / ".jade-genesis"))
)
LEARNING_GOALS_PATH = CONFIG_DIR / "skill-learning-goals.json"

Teacher = Callable[[dict[str, Any]], dict[str, Any]]


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _clean_id(value: Any, name: str) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:MAX_ID_CHARS]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _clean_reason(value: Any) -> str:
    return " ".join(str(value or "").replace("\x00", " ").split())[:MAX_REASON_CHARS]


def _validate_goal_contract(raw: Any, name: str) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError(f"invalid_{name}_contract")
    allowed = {"type", "required_fields", "description"}
    if any(key not in allowed for key in raw):
        raise ValueError(f"unsupported_{name}_contract_field")
    contract_type = str(raw.get("type", "any")).strip().lower()
    allowed_types = {"any", "object", "array", "string", "number", "integer", "boolean", "null"}
    if contract_type not in allowed_types:
        raise ValueError(f"unsupported_{name}_contract_type")
    required = raw.get("required_fields", [])
    if not isinstance(required, list) or len(required) > 64:
        raise ValueError(f"invalid_{name}_required_fields")
    fields: list[str] = []
    for item in required:
        field = _clean_id(item, f"{name}_required_field")
        if field not in fields:
            fields.append(field)
    return {
        "type": contract_type,
        "required_fields": fields,
        "description": _clean_reason(raw.get("description", ""))[:500],
    }


class LearningGoalStore:
    """Small atomic identity-bound store for skill acquisition goals."""

    def __init__(self, path: Path = LEARNING_GOALS_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "goals": {},
            "updated_at": 0,
        }

    def _decode(self, path: Path) -> dict[str, Any] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA_VERSION:
            return None
        if not isinstance(raw.get("goals", {}), dict):
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
        raise RuntimeError("learning_goal_store_corrupt")

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
            raise ValueError("learning_goal_identity_mismatch")
        if not bound:
            state["identity_id"] = requested

    def _touch(self, state: dict[str, Any], now: int) -> None:
        state["revision"] = max(0, int(state.get("revision", 0))) + 1
        state["updated_at"] = now
        self._save(state)

    def open_gap(
        self,
        identity_id: str,
        goal_id: str,
        task_family: str,
        dataset_id: str,
        input_contract: dict[str, Any],
        output_contract: dict[str, Any],
        *,
        reason: str,
        registry: Any,
        ledger: Any,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Create a goal only when the exact family really has no active skill."""

        goal_id = _clean_id(goal_id, "goal_id")
        family = _clean_id(task_family, "task_family")
        dataset_id = _clean_id(dataset_id, "dataset_id")
        if registry.selected_skill(identity_id, family) is not None:
            raise ValueError("learning_gap_not_present")
        manifest = ledger.sealed_manifest(identity_id, dataset_id)
        if not manifest.get("sealed"):
            raise ValueError("learning_dataset_must_be_sealed")
        if manifest.get("sealed_exam_consumed"):
            raise ValueError("learning_dataset_already_consumed")
        learning = ledger.learning_view(identity_id, dataset_id)
        if str(learning.get("task_family", "")) != family:
            raise ValueError("learning_dataset_family_mismatch")
        partitions = {str(case.get("partition", "")) for case in learning.get("cases", []) if isinstance(case, dict)}
        if "TRAIN" not in partitions or "VALIDATION" not in partitions:
            raise ValueError("learning_dataset_requires_train_and_validation")
        if not str(learning.get("sealed_set_sha256", "")):
            raise ValueError("learning_dataset_missing_sealed_commitment")

        input_contract = _validate_goal_contract(input_contract, "input")
        output_contract = _validate_goal_contract(output_contract, "output")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            goals = state["goals"]
            if goal_id in goals:
                raise ValueError("learning_goal_already_exists")
            if len(goals) >= MAX_GOALS:
                raise ValueError("learning_goal_limit_reached")
            goal = {
                "goal_id": goal_id,
                "task_family": family,
                "dataset_id": dataset_id,
                "reason": _clean_reason(reason),
                "input_contract": input_contract,
                "output_contract": output_contract,
                "state": "OPEN",
                "candidate_count": 0,
                "selected_spec_sha256": "",
                "sealed_exam_id": "",
                "retained_skill_id": "",
                "retained_skill_version": 0,
                "created_at": now,
                "updated_at": now,
            }
            goals[goal_id] = goal
            self._touch(state, now)
            return self._public(goal)

    def get(self, identity_id: str, goal_id: str) -> dict[str, Any]:
        goal_id = _clean_id(goal_id, "goal_id")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            goal = state["goals"].get(goal_id)
            if not isinstance(goal, dict):
                raise ValueError("learning_goal_not_found")
            return json.loads(json.dumps(goal))

    def update(
        self,
        identity_id: str,
        goal_id: str,
        changes: dict[str, Any],
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        goal_id = _clean_id(goal_id, "goal_id")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        allowed = {
            "state",
            "candidate_count",
            "selected_spec_sha256",
            "sealed_exam_id",
            "retained_skill_id",
            "retained_skill_version",
        }
        if any(key not in allowed for key in changes):
            raise ValueError("learning_goal_update_field_forbidden")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            goal = state["goals"].get(goal_id)
            if not isinstance(goal, dict):
                raise ValueError("learning_goal_not_found")
            for key, value in changes.items():
                goal[key] = value
            goal["updated_at"] = now
            self._touch(state, now)
            return self._public(goal)

    @staticmethod
    def _public(goal: dict[str, Any]) -> dict[str, Any]:
        return {
            "goal_id": goal.get("goal_id", ""),
            "task_family": goal.get("task_family", ""),
            "dataset_id": goal.get("dataset_id", ""),
            "reason": goal.get("reason", ""),
            "state": goal.get("state", ""),
            "candidate_count": max(0, int(goal.get("candidate_count", 0))),
            "selected_spec_sha256": goal.get("selected_spec_sha256", ""),
            "sealed_exam_id": goal.get("sealed_exam_id", ""),
            "retained_skill_id": goal.get("retained_skill_id", ""),
            "retained_skill_version": max(0, int(goal.get("retained_skill_version", 0))),
            "created_at": max(0, int(goal.get("created_at", 0))),
            "updated_at": max(0, int(goal.get("updated_at", 0))),
        }

    def status(self) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            goals = [item for item in state.get("goals", {}).values() if isinstance(item, dict)]
            by_state: dict[str, int] = {}
            for goal in goals:
                name = str(goal.get("state", "UNKNOWN"))
                by_state[name] = by_state.get(name, 0) + 1
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "goal_count": len(goals),
                "goals_by_state": by_state,
                "revision": max(0, int(state.get("revision", 0))),
                "updated_at": max(0, int(state.get("updated_at", 0))),
            }


class SkillSynthesisLoop:
    """Orchestrate visible learning, one frozen final exam and retention."""

    def __init__(self, ledger: Any, registry: Any, goals: LearningGoalStore):
        self.ledger = ledger
        self.registry = registry
        self.goals = goals

    @staticmethod
    def _teacher_request(
        goal: dict[str, Any],
        learning_view: dict[str, Any],
        previous_attempts: list[dict[str, Any]],
    ) -> dict[str, Any]:
        visible_cases = [
            {
                "case_id": case.get("case_id", ""),
                "partition": case.get("partition", ""),
                "input": case.get("input"),
                "expected_output": case.get("expected_output"),
            }
            for case in learning_view.get("cases", [])
            if isinstance(case, dict)
            and case.get("partition") in {"TRAIN", "VALIDATION"}
        ]
        return {
            "request_kind": "JADE_SKILL_TEACHER_REQUEST_V1",
            "goal_id": goal["goal_id"],
            "task_family": goal["task_family"],
            "reason": goal.get("reason", ""),
            "input_contract": goal["input_contract"],
            "output_contract": goal["output_contract"],
            "body_kind": BODY_KIND,
            "allowed_ops": sorted(ALLOWED_OPS),
            "dependencies_allowed": False,
            "visible_cases": visible_cases,
            "previous_attempts": previous_attempts,
            "sealed_set_sha256": learning_view.get("sealed_set_sha256", ""),
            "sealed_test_count": max(0, int(learning_view.get("sealed_test_count", 0))),
            "sealed_test_inputs_exposed": False,
            "sealed_test_answers_exposed": False,
            "seal_nonce_exposed": False,
            "required_response": {
                "skill_id": "short stable identifier",
                "description": "brief description",
                "domain": "verifiable_procedure",
                "body": {"op": "...", "args": []},
            },
        }

    @staticmethod
    def _build_candidate(
        goal: dict[str, Any],
        learning_view: dict[str, Any],
        proposal: dict[str, Any],
        *,
        teacher_id: str,
        source_model: str,
        now_ms: int,
    ) -> dict[str, Any]:
        if not isinstance(proposal, dict):
            raise ValueError("teacher_proposal_must_be_object")
        body = proposal.get("body")
        if not isinstance(body, dict):
            raise ValueError("teacher_proposal_body_required")
        skill_id = _clean_id(
            proposal.get("skill_id") or f"learned-{goal['goal_id']}",
            "skill_id",
        )
        raw_spec = {
            "schema_version": 1,
            "skill_id": skill_id,
            "skill_version": 1,
            "task_family": goal["task_family"],
            "domain": _clean_id(proposal.get("domain") or "verifiable_procedure", "domain"),
            "description": _clean_reason(proposal.get("description") or goal.get("reason", ""))[:500],
            "input_contract": goal["input_contract"],
            "output_contract": goal["output_contract"],
            "body_kind": BODY_KIND,
            "body": body,
            "dependencies": [],
            "provenance": {
                "source_kind": "EXTERNAL_TEACHER",
                "source_id": _clean_id(teacher_id, "teacher_id"),
                "source_model": _clean_reason(source_model)[:500],
                "created_at": now_ms,
            },
            "evaluation_policy": {
                "verifier_kind": "exact_json_v1",
                "sealed_set_sha256": str(learning_view.get("sealed_set_sha256", "")),
                "min_pass_rate": 1.0,
                "max_protected_failures": 0,
            },
        }
        return normalize_skill_spec(raw_spec)

    @staticmethod
    def _visible_evaluation(
        candidate: dict[str, Any],
        learning_view: dict[str, Any],
    ) -> dict[str, Any]:
        summary = {
            "TRAIN": {"passed": 0, "total": 0, "execution_failures": 0},
            "VALIDATION": {"passed": 0, "total": 0, "execution_failures": 0},
        }
        for case in learning_view.get("cases", []):
            if not isinstance(case, dict):
                continue
            partition = str(case.get("partition", ""))
            if partition not in summary:
                continue
            summary[partition]["total"] += 1
            try:
                execution = execute_skill(
                    candidate,
                    case.get("input"),
                    allowed_source_kinds={"EXTERNAL_TEACHER", "FUTURE_SYNTHESIS"},
                )
                passed = json.dumps(
                    execution["result"],
                    ensure_ascii=False,
                    separators=(",", ":"),
                    sort_keys=True,
                ) == json.dumps(
                    case.get("expected_output"),
                    ensure_ascii=False,
                    separators=(",", ":"),
                    sort_keys=True,
                )
            except Exception:
                passed = False
                summary[partition]["execution_failures"] += 1
            if passed:
                summary[partition]["passed"] += 1
        for partition in ("TRAIN", "VALIDATION"):
            total = summary[partition]["total"]
            summary[partition]["pass_rate"] = (
                summary[partition]["passed"] / total if total else 0.0
            )
        summary["all_visible_passed"] = all(
            summary[name]["total"] > 0
            and summary[name]["passed"] == summary[name]["total"]
            for name in ("TRAIN", "VALIDATION")
        )
        return summary

    def learn(
        self,
        identity_id: str,
        goal_id: str,
        teacher: Teacher,
        *,
        teacher_id: str,
        source_model: str = "",
        max_candidates: int = MAX_CANDIDATES,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Acquire one exact-family skill through visible iteration + final exam."""

        goal = self.goals.get(identity_id, goal_id)
        if goal.get("state") not in {"OPEN", "VISIBLE_TESTING"}:
            raise ValueError("learning_goal_not_open")
        if self.registry.selected_skill(identity_id, goal["task_family"]) is not None:
            raise ValueError("learning_gap_already_resolved")

        learning_view = self.ledger.learning_view(identity_id, goal["dataset_id"])
        if learning_view.get("sealed_exam_consumed"):
            raise ValueError("learning_dataset_already_consumed")
        if str(learning_view.get("task_family", "")) != goal["task_family"]:
            raise ValueError("learning_dataset_family_mismatch")

        limit = min(MAX_CANDIDATES, max(1, int(max_candidates)))
        base_now = _now_ms() if now_ms is None else max(0, int(now_ms))
        previous_attempts: list[dict[str, Any]] = []
        self.goals.update(identity_id, goal_id, {"state": "VISIBLE_TESTING"}, base_now)

        frozen: dict[str, Any] | None = None
        for index in range(limit):
            request = self._teacher_request(goal, learning_view, previous_attempts)
            proposal = teacher(json.loads(json.dumps(request)))
            try:
                candidate = self._build_candidate(
                    goal,
                    learning_view,
                    proposal,
                    teacher_id=teacher_id,
                    source_model=source_model,
                    now_ms=base_now + index,
                )
                visible = self._visible_evaluation(candidate, learning_view)
                feedback = {
                    "candidate_number": index + 1,
                    "candidate_spec_sha256": candidate["spec_sha256"],
                    "train_pass_rate": visible["TRAIN"]["pass_rate"],
                    "validation_pass_rate": visible["VALIDATION"]["pass_rate"],
                    "visible_execution_failures": (
                        visible["TRAIN"]["execution_failures"]
                        + visible["VALIDATION"]["execution_failures"]
                    ),
                    "all_visible_passed": bool(visible["all_visible_passed"]),
                }
            except Exception as exc:
                candidate = None
                feedback = {
                    "candidate_number": index + 1,
                    "candidate_spec_sha256": "",
                    "train_pass_rate": 0.0,
                    "validation_pass_rate": 0.0,
                    "visible_execution_failures": 1,
                    "all_visible_passed": False,
                    "proposal_rejected": type(exc).__name__,
                }
            previous_attempts.append(feedback)
            self.goals.update(
                identity_id,
                goal_id,
                {"candidate_count": index + 1},
                base_now + index,
            )
            if candidate is not None and feedback["all_visible_passed"]:
                frozen = candidate
                break

        if frozen is None:
            self.goals.update(
                identity_id,
                goal_id,
                {"state": "VISIBLE_FAILED"},
                base_now + limit,
            )
            return {
                "goal_id": goal_id,
                "state": "VISIBLE_FAILED",
                "candidate_count": len(previous_attempts),
                "visible_attempts": previous_attempts,
                "sealed_exam_run": False,
                "skill_retained": False,
                "teacher_saw_sealed_test": False,
            }

        self.goals.update(
            identity_id,
            goal_id,
            {
                "state": "FROZEN",
                "selected_spec_sha256": frozen["spec_sha256"],
            },
            base_now + len(previous_attempts),
        )
        exam = self.ledger.run_sealed_skill_exam(
            identity_id,
            goal["dataset_id"],
            frozen,
            now_ms=base_now + len(previous_attempts) + 1,
        )
        if not exam.get("verdict"):
            self.goals.update(
                identity_id,
                goal_id,
                {
                    "state": "SEALED_FAILED",
                    "sealed_exam_id": exam.get("exam_id", ""),
                },
                base_now + len(previous_attempts) + 2,
            )
            return {
                "goal_id": goal_id,
                "state": "SEALED_FAILED",
                "candidate_count": len(previous_attempts),
                "selected_spec_sha256": frozen["spec_sha256"],
                "sealed_exam": exam,
                "skill_retained": False,
                "teacher_saw_sealed_test": False,
            }

        retained = self.registry.register_verified_skill(
            identity_id,
            frozen,
            ledger=self.ledger,
            dataset_id=goal["dataset_id"],
            activate=True,
            now_ms=base_now + len(previous_attempts) + 3,
        )
        self.goals.update(
            identity_id,
            goal_id,
            {
                "state": "RETAINED",
                "sealed_exam_id": exam.get("exam_id", ""),
                "retained_skill_id": retained["skill_id"],
                "retained_skill_version": retained["skill_version"],
            },
            base_now + len(previous_attempts) + 4,
        )
        return {
            "goal_id": goal_id,
            "state": "RETAINED",
            "candidate_count": len(previous_attempts),
            "selected_spec_sha256": frozen["spec_sha256"],
            "sealed_exam": exam,
            "retained_skill": retained,
            "skill_retained": True,
            "teacher_saw_sealed_test": False,
            "teacher_controls_verifier": False,
            "teacher_controls_activation": False,
        }


def skill_synthesis_status(goals: LearningGoalStore | None = None) -> dict[str, Any]:
    store = goals or LearningGoalStore()
    try:
        goal_status = store.status()
    except RuntimeError:
        goal_status = {"healthy": False, "error": "learning_goal_store_corrupt"}
    return {
        "schema_version": SCHEMA_VERSION,
        "learning_goal_store": goal_status,
        "teacher_role": "proposal_only",
        "teacher_controls_provenance": False,
        "teacher_controls_verifier": False,
        "teacher_controls_activation": False,
        "visible_iteration_partitions": ["TRAIN", "VALIDATION"],
        "sealed_test_visible_to_teacher": False,
        "sealed_test_final_exam_single_use": True,
        "candidate_dependencies_allowed": False,
        "candidate_runtime": BODY_KIND,
        "candidate_limit": MAX_CANDIDATES,
        "arbitrary_code_execution": False,
        "shell_access": False,
        "filesystem_access": False,
        "model_weight_mutation": False,
        "autonomous_triggering": False,
    }
