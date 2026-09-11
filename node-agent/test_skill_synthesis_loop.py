from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from skill_registry import SkillRegistry
from skill_synthesis_loop import LearningGoalStore, SkillSynthesisLoop
from skill_spec import BODY_KIND
from verifiable_task_ledger import VerifiableTaskLedger


class SkillSynthesisLoopTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-synthesis-")
        root = Path(self.temp.name)
        self.ledger_path = root / "ledger.json"
        self.registry_path = root / "registry.json"
        self.goals_path = root / "goals.json"
        self.ledger = VerifiableTaskLedger(self.ledger_path)
        self.registry = SkillRegistry(self.registry_path)
        self.goals = LearningGoalStore(self.goals_path)
        self.loop = SkillSynthesisLoop(self.ledger, self.registry, self.goals)
        self.identity = "jade-test"
        self.family = "learned_json_sum"
        self.dataset = "sum-dataset-v1"

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def input_contract() -> dict:
        return {
            "type": "object",
            "required_fields": ["a", "b"],
            "description": "Two numbers to add.",
        }

    @staticmethod
    def output_contract() -> dict:
        return {
            "type": "object",
            "required_fields": ["value"],
            "description": "Object containing their sum.",
        }

    @staticmethod
    def correct_body() -> dict:
        return {
            "op": "object",
            "args": [
                {"op": "literal", "args": ["value"]},
                {
                    "op": "add",
                    "args": [
                        {
                            "op": "get",
                            "args": [{"op": "literal", "args": ["a"]}],
                        },
                        {
                            "op": "get",
                            "args": [{"op": "literal", "args": ["b"]}],
                        },
                    ],
                },
            ],
        }

    def make_dataset(self, *, hidden_expected_override: dict | None = None) -> None:
        self.ledger.create_dataset(
            self.identity,
            self.dataset,
            self.family,
            now_ms=1_000,
        )
        cases = [
            ("train-1", "TRAIN", {"a": 1, "b": 2}, {"value": 3}),
            ("train-2", "TRAIN", {"a": 5, "b": 7}, {"value": 12}),
            ("validation-1", "VALIDATION", {"a": 11, "b": 4}, {"value": 15}),
            ("sealed-1", "SEALED_TEST", {"a": 101, "b": 203}, {"value": 304}),
            ("sealed-2", "SEALED_TEST", {"a": 409, "b": 503}, {"value": 912}),
        ]
        for index, (case_id, partition, input_value, expected) in enumerate(cases):
            if partition == "SEALED_TEST" and hidden_expected_override is not None:
                expected = hidden_expected_override
            self.ledger.add_case(
                self.identity,
                self.dataset,
                case_id,
                partition,
                input_value,
                expected,
                now_ms=1_100 + index,
            )
        self.ledger.seal_dataset(self.identity, self.dataset, now_ms=1_200)

    def open_goal(self) -> None:
        self.goals.open_gap(
            self.identity,
            "goal-sum-v1",
            self.family,
            self.dataset,
            self.input_contract(),
            self.output_contract(),
            reason="Acquire a deterministic procedure that adds a and b.",
            registry=self.registry,
            ledger=self.ledger,
            now_ms=1_300,
        )

    def test_acquires_retains_restarts_and_reuses_without_teacher(self) -> None:
        self.make_dataset()
        self.open_goal()
        teacher_requests: list[dict] = []

        def teacher(request: dict) -> dict:
            teacher_requests.append(request)
            if len(teacher_requests) == 1:
                return {
                    "skill_id": "learned-sum",
                    "description": "First attempt is intentionally wrong.",
                    "body": {
                        "op": "object",
                        "args": [
                            {"op": "literal", "args": ["value"]},
                            {"op": "literal", "args": [0]},
                        ],
                    },
                    "provenance": {"source_kind": "DEVELOPER"},
                    "dependencies": ["unsafe"],
                    "evaluation_policy": {"min_pass_rate": 0.0},
                }
            return {
                "skill_id": "learned-sum",
                "description": "Add the two input fields.",
                "domain": "verifiable_procedure",
                "body": self.correct_body(),
            }

        result = self.loop.learn(
            self.identity,
            "goal-sum-v1",
            teacher,
            teacher_id="teacher-test",
            source_model="deterministic-test-teacher",
            max_candidates=3,
            now_ms=2_000,
        )
        self.assertEqual("RETAINED", result["state"])
        self.assertEqual(2, result["candidate_count"])
        self.assertTrue(result["skill_retained"])
        self.assertTrue(result["sealed_exam"]["verdict"])
        self.assertFalse(result["teacher_saw_sealed_test"])
        self.assertFalse(result["teacher_controls_verifier"])
        self.assertFalse(result["teacher_controls_activation"])
        self.assertEqual(2, len(teacher_requests))

        # Teacher requests contain TRAIN/VALIDATION only. The commitment hash is
        # allowed, so isolation must be checked structurally rather than by
        # searching for numeric substrings that may occur inside SHA-256 text.
        for request in teacher_requests:
            self.assertFalse(request["sealed_test_inputs_exposed"])
            self.assertFalse(request["sealed_test_answers_exposed"])
            self.assertFalse(request["seal_nonce_exposed"])
            self.assertEqual(2, request["sealed_test_count"])
            visible_ids = {case["case_id"] for case in request["visible_cases"]}
            self.assertEqual(
                {"train-1", "train-2", "validation-1"},
                visible_ids,
            )
            self.assertNotIn("sealed-1", visible_ids)
            self.assertNotIn("sealed-2", visible_ids)
            self.assertTrue(
                all(
                    case["partition"] in {"TRAIN", "VALIDATION"}
                    for case in request["visible_cases"]
                )
            )
            self.assertNotIn(
                {"a": 101, "b": 203},
                [case["input"] for case in request["visible_cases"]],
            )
            self.assertNotIn(
                {"a": 409, "b": 503},
                [case["input"] for case in request["visible_cases"]],
            )

        selected = self.registry.selected_skill(self.identity, self.family)
        self.assertIsNotNone(selected)
        self.assertEqual("EXTERNAL_TEACHER", selected["source_kind"])
        self.assertTrue(selected["sealed_verified"])

        # Hard logical restart: construct fresh store/runtime-facing objects from
        # disk. The teacher callable is intentionally never passed to this path.
        restarted_registry = SkillRegistry(self.registry_path)
        restarted_ledger = VerifiableTaskLedger(self.ledger_path)
        restarted_goals = LearningGoalStore(self.goals_path)
        self.assertTrue(
            restarted_ledger.sealed_manifest(self.identity, self.dataset)[
                "sealed_exam_consumed"
            ]
        )
        self.assertEqual(
            "RETAINED",
            restarted_goals.get(self.identity, "goal-sum-v1")["state"],
        )
        recall = restarted_registry.execute_for_family(
            self.identity,
            self.family,
            {"a": 20, "b": 22},
        )
        self.assertEqual({"value": 42}, recall["execution"]["result"])
        self.assertTrue(recall["verified_learned_skill"])
        self.assertEqual("EXTERNAL_TEACHER", recall["selected_source_kind"])
        self.assertFalse(recall["llm_used"])
        self.assertFalse(recall["network_used"])
        self.assertEqual(2, len(teacher_requests))

        # Causal control: removing the retained route removes the capability.
        restarted_registry.deactivate_family(
            self.identity,
            self.family,
            approved=True,
            now_ms=3_000,
        )
        with self.assertRaisesRegex(
            LookupError,
            "skill_registry_no_active_skill_for_family",
        ):
            restarted_registry.execute_for_family(
                self.identity,
                self.family,
                {"a": 20, "b": 22},
            )

    def test_hidden_failure_burns_dataset_and_does_not_retain(self) -> None:
        self.make_dataset(hidden_expected_override={"value": -999})
        self.open_goal()
        calls = 0

        def teacher(request: dict) -> dict:
            nonlocal calls
            calls += 1
            return {
                "skill_id": "learned-sum-hidden-fail",
                "description": "Passes visible examples only.",
                "body": self.correct_body(),
            }

        result = self.loop.learn(
            self.identity,
            "goal-sum-v1",
            teacher,
            teacher_id="teacher-test",
            max_candidates=2,
            now_ms=2_000,
        )
        self.assertEqual("SEALED_FAILED", result["state"])
        self.assertFalse(result["skill_retained"])
        self.assertEqual(1, calls)
        self.assertTrue(
            self.ledger.sealed_manifest(self.identity, self.dataset)[
                "sealed_exam_consumed"
            ]
        )
        self.assertIsNone(self.registry.selected_skill(self.identity, self.family))

        with self.assertRaisesRegex(ValueError, "learning_goal_not_open"):
            self.loop.learn(
                self.identity,
                "goal-sum-v1",
                teacher,
                teacher_id="teacher-test",
                max_candidates=2,
                now_ms=3_000,
            )

    def test_visible_failure_does_not_consume_sealed_exam(self) -> None:
        self.make_dataset()
        self.open_goal()

        def teacher(request: dict) -> dict:
            return {
                "skill_id": "never-valid",
                "description": "Always wrong.",
                "body": {
                    "op": "object",
                    "args": [
                        {"op": "literal", "args": ["value"]},
                        {"op": "literal", "args": [-1]},
                    ],
                },
            }

        result = self.loop.learn(
            self.identity,
            "goal-sum-v1",
            teacher,
            teacher_id="teacher-test",
            max_candidates=2,
            now_ms=2_000,
        )
        self.assertEqual("VISIBLE_FAILED", result["state"])
        self.assertFalse(result["sealed_exam_run"])
        self.assertFalse(
            self.ledger.sealed_manifest(self.identity, self.dataset)[
                "sealed_exam_consumed"
            ]
        )

    def test_gap_cannot_open_when_family_already_has_active_skill(self) -> None:
        self.make_dataset()
        developer = {
            "schema_version": 1,
            "skill_id": "developer-sum",
            "skill_version": 1,
            "task_family": self.family,
            "domain": "verifiable_procedure",
            "description": "Already available.",
            "input_contract": self.input_contract(),
            "output_contract": self.output_contract(),
            "body_kind": BODY_KIND,
            "body": self.correct_body(),
            "dependencies": [],
            "provenance": {
                "source_kind": "DEVELOPER",
                "source_id": "test",
                "source_model": "",
                "created_at": 1_000,
            },
            "evaluation_policy": {
                "verifier_kind": "exact_json_v1",
                "sealed_set_sha256": self.ledger.sealed_manifest(
                    self.identity, self.dataset
                )["sealed_set_sha256"],
                "min_pass_rate": 1.0,
                "max_protected_failures": 0,
            },
        }
        self.registry.register_developer_skill(self.identity, developer, now_ms=1_300)
        self.registry.activate_developer_skill(
            self.identity,
            "developer-sum",
            1,
            approved=True,
            now_ms=1_400,
        )
        with self.assertRaisesRegex(ValueError, "learning_gap_not_present"):
            self.open_goal()


if __name__ == "__main__":
    unittest.main()
