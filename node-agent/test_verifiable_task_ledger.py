from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from skill_spec import BODY_KIND
from verifiable_task_ledger import VerifiableTaskLedger


class VerifiableTaskLedgerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-task-ledger-")
        self.path = Path(self.temp.name) / "ledger.json"
        self.ledger = VerifiableTaskLedger(self.path)
        self.identity = "jade-test"
        self.dataset = "synthetic-json-v1"
        self.ledger.create_dataset(
            self.identity,
            self.dataset,
            "synthetic_json_transform",
            now_ms=1_000,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def add_three_partitions(self) -> None:
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "train-1",
            "TRAIN",
            {"a": 2, "b": 3},
            {"value": 5},
            now_ms=1_100,
        )
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "validation-1",
            "VALIDATION",
            {"a": 4, "b": 5},
            {"value": 9},
            now_ms=1_200,
        )
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "sealed-1",
            "SEALED_TEST",
            {"a": 10, "b": 7},
            {"value": 17},
            now_ms=1_300,
        )

    @staticmethod
    def developer_sum_skill(
        sealed_hash: str,
        *,
        bias: int = 0,
        source_kind: str = "DEVELOPER",
    ) -> dict:
        value_expr: dict = {
            "op": "add",
            "args": [
                {"op": "get", "args": [{"op": "literal", "args": ["a"]}]},
                {"op": "get", "args": [{"op": "literal", "args": ["b"]}]},
            ],
        }
        if bias:
            value_expr = {
                "op": "add",
                "args": [
                    value_expr,
                    {"op": "literal", "args": [bias]},
                ],
            }
        return {
            "schema_version": 1,
            "skill_id": f"sum-fields-{bias}",
            "skill_version": 1,
            "task_family": "synthetic_json_transform",
            "domain": "verifiable_procedure",
            "description": "Deterministically sum a and b.",
            "input_contract": {
                "type": "object",
                "required_fields": ["a", "b"],
            },
            "output_contract": {
                "type": "object",
                "required_fields": ["value"],
            },
            "body_kind": BODY_KIND,
            "body": {
                "op": "object",
                "args": [
                    {"op": "literal", "args": ["value"]},
                    value_expr,
                ],
            },
            "dependencies": [],
            "provenance": {
                "source_kind": source_kind,
                "source_id": "ledger-test",
                "source_model": "",
                "created_at": 1_000,
            },
            "evaluation_policy": {
                "verifier_kind": "exact_json_v1",
                "sealed_set_sha256": sealed_hash,
                "min_pass_rate": 1.0,
                "max_protected_failures": 0,
            },
        }

    def test_learning_view_never_exposes_sealed_test_case_or_nonce(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        view = self.ledger.learning_view(self.identity, self.dataset)

        self.assertTrue(manifest["sealed"])
        self.assertEqual(64, len(manifest["sealed_set_sha256"]))
        self.assertEqual(1, manifest["sealed_test_count"])
        self.assertFalse(manifest["sealed_test_inputs_exposed"])
        self.assertFalse(manifest["sealed_test_answers_exposed"])
        self.assertFalse(manifest["seal_nonce_exposed"])
        self.assertFalse(manifest["sealed_exam_consumed"])
        self.assertNotIn("seal_nonce", manifest)
        self.assertEqual(2, len(view["cases"]))
        self.assertEqual(
            {"TRAIN", "VALIDATION"},
            {case["partition"] for case in view["cases"]},
        )
        self.assertFalse(view["sealed_test_inputs_exposed"])
        self.assertFalse(view["sealed_test_answers_exposed"])
        self.assertFalse(view["seal_nonce_exposed"])
        self.assertNotIn("seal_nonce", view)
        self.assertNotIn(
            "sealed-1",
            {case["case_id"] for case in view["cases"]},
        )

    def test_adding_sealed_case_does_not_return_hidden_input_or_answer_digest(self) -> None:
        result = self.ledger.add_case(
            self.identity,
            self.dataset,
            "sealed-1",
            "SEALED_TEST",
            {"secret_input": 8},
            {"value": 16},
            now_ms=1_100,
        )
        self.assertTrue(result["hidden"])
        self.assertNotIn("input_sha256", result)
        self.assertNotIn("expected_output_sha256", result)
        self.assertNotIn("input", result)
        self.assertNotIn("expected_output", result)

    def test_sealed_dataset_is_immutable(self) -> None:
        self.add_three_partitions()
        self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        with self.assertRaisesRegex(PermissionError, "sealed_dataset_is_immutable"):
            self.ledger.add_case(
                self.identity,
                self.dataset,
                "late-train",
                "TRAIN",
                {"a": 1},
                {"value": 1},
                now_ms=2_100,
            )

    def test_sealed_test_is_never_interactively_evaluable(self) -> None:
        self.add_three_partitions()
        with self.assertRaisesRegex(
            PermissionError,
            "sealed_test_case_evaluation_forbidden",
        ):
            self.ledger.evaluate_case(
                self.identity,
                self.dataset,
                "sealed-1",
                {"value": 17},
                "candidate_skill",
                "skill-1",
                now_ms=1_400,
            )

        self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        with self.assertRaisesRegex(
            PermissionError,
            "sealed_test_case_evaluation_forbidden",
        ):
            self.ledger.evaluate_case(
                self.identity,
                self.dataset,
                "sealed-1",
                {"value": 17},
                "candidate_skill",
                "skill-1",
                now_ms=2_100,
            )

    def test_exact_json_validation_verifier_is_canonical(self) -> None:
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "validation-1",
            "VALIDATION",
            {"x": 1},
            {"a": 1, "b": [2, 3]},
            now_ms=1_100,
        )
        result = self.ledger.evaluate_case(
            self.identity,
            self.dataset,
            "validation-1",
            {"b": [2, 3], "a": 1},
            "manual_baseline",
            "baseline-1",
            now_ms=1_200,
        )
        self.assertTrue(result["verdict"])

    def test_final_exam_requires_sealed_dataset(self) -> None:
        self.add_three_partitions()
        skill = self.developer_sum_skill("a" * 64)
        with self.assertRaisesRegex(
            PermissionError,
            "sealed_test_must_be_committed_before_final_exam",
        ):
            self.ledger.run_sealed_skill_exam(
                self.identity,
                self.dataset,
                skill,
                now_ms=1_500,
            )

    def test_final_exam_requires_exact_sealed_commitment(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        skill = self.developer_sum_skill("b" * 64)
        self.assertNotEqual("b" * 64, manifest["sealed_set_sha256"])
        with self.assertRaisesRegex(ValueError, "skill_sealed_set_mismatch"):
            self.ledger.run_sealed_skill_exam(
                self.identity,
                self.dataset,
                skill,
                now_ms=2_100,
            )
        self.assertFalse(
            self.ledger.sealed_manifest(self.identity, self.dataset)[
                "sealed_exam_consumed"
            ]
        )

    def test_final_exam_returns_only_aggregate_verdict(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        skill = self.developer_sum_skill(manifest["sealed_set_sha256"])
        result = self.ledger.run_sealed_skill_exam(
            self.identity,
            self.dataset,
            skill,
            now_ms=2_100,
        )

        self.assertTrue(result["verdict"])
        self.assertTrue(result["dataset_consumed"])
        self.assertFalse(result["replayed"])
        self.assertFalse(result["feedback_detail_exposed"])
        self.assertFalse(result["per_case_verdicts_exposed"])
        self.assertFalse(result["expected_outputs_exposed"])
        self.assertFalse(result["seal_nonce_exposed"])
        for forbidden in (
            "passed_count",
            "failed_count",
            "pass_rate",
            "case_id",
            "case_results",
            "expected_output",
            "expected_output_sha256",
            "seal_nonce",
        ):
            self.assertNotIn(forbidden, result)

    def test_external_teacher_candidate_can_run_only_in_sealed_exam(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        skill = self.developer_sum_skill(
            manifest["sealed_set_sha256"],
            source_kind="EXTERNAL_TEACHER",
        )
        result = self.ledger.run_sealed_skill_exam(
            self.identity,
            self.dataset,
            skill,
            now_ms=2_100,
        )
        self.assertTrue(result["verdict"])
        proof = self.ledger.verified_exam_for_candidate(
            self.identity,
            self.dataset,
            result["candidate_spec_sha256"],
        )
        self.assertTrue(proof["verdict"])
        self.assertEqual("synthetic_json_transform", proof["task_family"])
        self.assertNotIn("passed_count", proof)
        self.assertNotIn("expected_output", proof)

    def test_same_frozen_candidate_replay_is_idempotent(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        skill = self.developer_sum_skill(manifest["sealed_set_sha256"])
        first = self.ledger.run_sealed_skill_exam(
            self.identity,
            self.dataset,
            skill,
            now_ms=2_100,
        )
        second = self.ledger.run_sealed_skill_exam(
            self.identity,
            self.dataset,
            skill,
            now_ms=9_999,
        )
        self.assertEqual(first["exam_id"], second["exam_id"])
        self.assertEqual(first["verdict"], second["verdict"])
        self.assertTrue(second["replayed"])

    def test_different_candidate_cannot_reuse_consumed_sealed_dataset(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        first = self.developer_sum_skill(manifest["sealed_set_sha256"])
        second = self.developer_sum_skill(
            manifest["sealed_set_sha256"],
            bias=1,
        )
        self.ledger.run_sealed_skill_exam(
            self.identity,
            self.dataset,
            first,
            now_ms=2_100,
        )
        with self.assertRaisesRegex(
            PermissionError,
            "sealed_dataset_exam_already_consumed",
        ):
            self.ledger.run_sealed_skill_exam(
                self.identity,
                self.dataset,
                second,
                now_ms=2_200,
            )

    def test_failed_candidate_also_consumes_sealed_dataset(self) -> None:
        self.add_three_partitions()
        manifest = self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        wrong = self.developer_sum_skill(
            manifest["sealed_set_sha256"],
            bias=1,
        )
        result = self.ledger.run_sealed_skill_exam(
            self.identity,
            self.dataset,
            wrong,
            now_ms=2_100,
        )
        self.assertFalse(result["verdict"])
        self.assertTrue(result["dataset_consumed"])
        status = self.ledger.status()
        self.assertEqual(1, status["sealed_exam_consumed_count"])

    def test_identity_binding_rejects_another_jade(self) -> None:
        with self.assertRaisesRegex(
            ValueError,
            "verifiable_task_ledger_identity_mismatch",
        ):
            self.ledger.learning_view("other-jade", self.dataset)

    def test_conversation_memory_fields_are_rejected(self) -> None:
        with self.assertRaisesRegex(
            ValueError,
            "task_ledger_not_conversation_memory",
        ):
            self.ledger.add_case(
                self.identity,
                self.dataset,
                "bad-1",
                "TRAIN",
                {"raw_conversation_text": "private chat"},
                {"value": 1},
                now_ms=1_100,
            )

    def test_backup_recovers_from_corrupt_primary(self) -> None:
        self.add_three_partitions()
        self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        self.assertTrue(self.ledger.backup_path.exists())
        self.path.write_text("{not-json", encoding="utf-8")
        status = VerifiableTaskLedger(self.path).status()
        self.assertEqual(1, status["dataset_count"])
        self.assertTrue(status["sealed_commitment_salted"])
        self.assertFalse(status["sealed_test_inputs_exposed"])
        self.assertFalse(status["sealed_test_answers_exposed"])
        self.assertFalse(status["seal_nonce_exposed"])
        self.assertFalse(status["sealed_case_evaluation_allowed"])
        self.assertTrue(status["sealed_final_exam_single_use"])
        self.assertEqual(
            "aggregate_pass_fail_only",
            status["sealed_final_exam_feedback"],
        )
        self.assertTrue(status["generated_skill_exam_execution"])
        self.assertFalse(status["generated_skill_general_execution"])
        self.assertFalse(status["arbitrary_code_execution"])


if __name__ == "__main__":
    unittest.main()
