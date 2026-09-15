"""Crash-recoverable production Skill Synthesis wrapper for 0.1.21 V2.

The base synthesis loop is intentionally small and independently testable. This
production wrapper tightens two proof-critical boundaries discovered by the
second adversarial audit:

1. a teacher-selected Skill key is preflighted before SEALED_TEST can run;
2. the complete frozen source SkillSpec is persisted before hidden execution.

A process interruption after freezing can therefore be reviewed and resumed
with the exact same candidate. No teacher is called during `resume_frozen()` and
`run_sealed_skill_exam()` remains idempotent for the exact frozen spec hash.
"""

from __future__ import annotations

import json
import time
from typing import Any

from skill_spec import normalize_skill_spec
from skill_synthesis_loop import MAX_CANDIDATES, SkillSynthesisLoop


def _now_ms() -> int:
    return int(time.time() * 1_000)


class ResilientSkillSynthesisLoop(SkillSynthesisLoop):
    """Production synthesis loop with pre-exam admission and frozen recovery."""

    def _finalize_frozen(
        self,
        identity_id: str,
        goal_id: str,
        *,
        now_ms: int,
    ) -> dict[str, Any]:
        goal = self.goals.get(identity_id, goal_id)
        if str(goal.get("state", "")) != "FROZEN":
            raise ValueError("learning_goal_not_frozen")
        frozen_source = goal.get("frozen_spec")
        if not isinstance(frozen_source, dict):
            raise RuntimeError("frozen_candidate_spec_missing")
        normalized = normalize_skill_spec(frozen_source)
        selected_hash = str(goal.get("selected_spec_sha256", "")).lower()
        if not selected_hash or normalized.get("spec_sha256") != selected_hash:
            raise RuntimeError("frozen_candidate_hash_mismatch")
        if str(normalized.get("task_family", "")) != str(goal.get("task_family", "")):
            raise RuntimeError("frozen_candidate_family_mismatch")

        learning_view = self.ledger.learning_view(identity_id, goal["dataset_id"])
        if str(learning_view.get("task_family", "")) != str(goal["task_family"]):
            raise RuntimeError("frozen_candidate_dataset_family_mismatch")
        commitment = str(learning_view.get("sealed_set_sha256", ""))
        policy_commitment = str(
            normalized.get("evaluation_policy", {}).get("sealed_set_sha256", "")
        )
        if not commitment or commitment != policy_commitment:
            raise RuntimeError("frozen_candidate_commitment_mismatch")

        # This may report an existing exact learned copy after a crash between
        # archive persistence and route activation. A different occupant is a
        # hard error and, critically, is detected before an unconsumed exam.
        self.registry.preflight_verified_candidate(identity_id, frozen_source)

        exam = self.ledger.run_sealed_skill_exam(
            identity_id,
            goal["dataset_id"],
            frozen_source,
            now_ms=now_ms,
        )
        if not exam.get("verdict"):
            self.goals.update(
                identity_id,
                goal_id,
                {
                    "state": "SEALED_FAILED",
                    "sealed_exam_id": exam.get("exam_id", ""),
                },
                now_ms + 1,
            )
            return {
                "goal_id": goal_id,
                "state": "SEALED_FAILED",
                "candidate_count": max(0, int(goal.get("candidate_count", 0))),
                "visible_attempts": goal.get("visible_attempts", []),
                "selected_spec_sha256": selected_hash,
                "sealed_exam": exam,
                "skill_retained": False,
                "teacher_saw_sealed_test": False,
                "recovered_frozen_candidate": bool(exam.get("replayed")),
            }

        retained = self.registry.register_verified_skill(
            identity_id,
            frozen_source,
            ledger=self.ledger,
            dataset_id=goal["dataset_id"],
            activate=True,
            now_ms=now_ms + 2,
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
            now_ms + 3,
        )
        return {
            "goal_id": goal_id,
            "state": "RETAINED",
            "candidate_count": max(0, int(goal.get("candidate_count", 0))),
            "visible_attempts": goal.get("visible_attempts", []),
            "selected_spec_sha256": selected_hash,
            "sealed_exam": exam,
            "retained_skill": retained,
            "skill_retained": True,
            "teacher_saw_sealed_test": False,
            "teacher_controls_verifier": False,
            "teacher_controls_activation": False,
            "recovered_frozen_candidate": bool(exam.get("replayed")),
        }

    def resume_frozen(
        self,
        identity_id: str,
        goal_id: str,
        *,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Resume only the exact persisted frozen candidate; never call teacher."""

        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        return self._finalize_frozen(identity_id, goal_id, now_ms=now)

    def learn(
        self,
        identity_id: str,
        goal_id: str,
        teacher: Any,
        *,
        teacher_id: str,
        source_model: str = "",
        max_candidates: int = MAX_CANDIDATES,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Acquire one skill, failing closed on interrupted visible re-entry."""

        goal = self.goals.get(identity_id, goal_id)
        state = str(goal.get("state", ""))
        if state == "FROZEN":
            raise ValueError("frozen_goal_requires_explicit_resume")
        if state == "VISIBLE_TESTING":
            raise ValueError("visible_testing_goal_requires_manual_review")
        if state != "OPEN":
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
        self.goals.update(
            identity_id,
            goal_id,
            {"state": "VISIBLE_TESTING", "visible_attempts": []},
            base_now,
        )

        frozen: dict[str, Any] | None = None
        frozen_source: dict[str, Any] | None = None
        for index in range(limit):
            request = self._teacher_request(goal, learning_view, previous_attempts)
            proposal = teacher(json.loads(json.dumps(request)))
            proposal_body = self._proposal_feedback_body(proposal)
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
                    "proposal_body": proposal_body,
                    "train_pass_rate": visible["TRAIN"]["pass_rate"],
                    "validation_pass_rate": visible["VALIDATION"]["pass_rate"],
                    "visible_execution_failures": (
                        visible["TRAIN"]["execution_failures"]
                        + visible["VALIDATION"]["execution_failures"]
                    ),
                    "visible_failures": visible["visible_failures"],
                    "all_visible_passed": bool(visible["all_visible_passed"]),
                }
            except Exception as exc:
                candidate = None
                feedback = {
                    "candidate_number": index + 1,
                    "candidate_spec_sha256": "",
                    "proposal_body": proposal_body,
                    "train_pass_rate": 0.0,
                    "validation_pass_rate": 0.0,
                    "visible_execution_failures": 1,
                    "visible_failures": [],
                    "all_visible_passed": False,
                    "proposal_rejected": type(exc).__name__,
                    "proposal_error": str(exc)[:240],
                }

            if candidate is not None and feedback["all_visible_passed"]:
                candidate_source = self._source_contract(candidate)
                try:
                    self.registry.preflight_verified_candidate(identity_id, candidate_source)
                except ValueError as exc:
                    # A teacher-controlled identifier collision is visible and
                    # correctable without consuming hidden evidence. Capacity or
                    # persistence faults are not teacher mistakes and fail closed.
                    if str(exc) == "skill_registry_candidate_slot_occupied":
                        feedback["all_visible_passed"] = False
                        feedback["pre_exam_admissible"] = False
                        feedback["pre_exam_rejected"] = str(exc)
                    else:
                        raise
                else:
                    feedback["pre_exam_admissible"] = True
                    frozen = candidate
                    frozen_source = candidate_source

            previous_attempts.append(feedback)
            self.goals.update(
                identity_id,
                goal_id,
                {
                    "candidate_count": index + 1,
                    "visible_attempts": previous_attempts,
                },
                base_now + index,
            )
            if frozen is not None and frozen_source is not None:
                break

        if frozen is None or frozen_source is None:
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

        self.goals.freeze_candidate(
            identity_id,
            goal_id,
            frozen_source,
            frozen["spec_sha256"],
            now_ms=base_now + len(previous_attempts),
        )
        return self._finalize_frozen(
            identity_id,
            goal_id,
            now_ms=base_now + len(previous_attempts) + 1,
        )
