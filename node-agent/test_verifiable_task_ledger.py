from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

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
        self.assertNotIn("seal_nonce", manifest)
        self.assertEqual(2, len(view["cases"]))
        self.assertEqual({"TRAIN", "VALIDATION"}, {case["partition"] for case in view["cases"]})
        self.assertFalse(view["sealed_test_inputs_exposed"])
        self.assertFalse(view["sealed_test_answers_exposed"])
        self.assertFalse(view["seal_nonce_exposed"])
        self.assertNotIn("seal_nonce", view)
        self.assertNotIn("sealed-1", {case["case_id"] for case in view["cases"]})

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

    def test_sealed_test_cannot_be_evaluated_before_commitment(self) -> None:
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "sealed-1",
            "SEALED_TEST",
            {"a": 8},
            {"value": 16},
            now_ms=1_100,
        )
        with self.assertRaisesRegex(PermissionError, "sealed_test_must_be_committed_before_evaluation"):
            self.ledger.evaluate_case(
                self.identity,
                self.dataset,
                "sealed-1",
                {"value": 16},
                "candidate_skill",
                "skill-1",
                now_ms=1_200,
            )

    def test_sealed_evaluator_returns_verdict_but_not_expected_answer(self) -> None:
        self.add_three_partitions()
        self.ledger.seal_dataset(self.identity, self.dataset, now_ms=2_000)
        passed = self.ledger.evaluate_case(
            self.identity,
            self.dataset,
            "sealed-1",
            {"value": 17},
            "candidate_skill",
            "skill-1",
            now_ms=2_100,
        )
        failed = self.ledger.evaluate_case(
            self.identity,
            self.dataset,
            "sealed-1",
            {"value": 999},
            "candidate_skill",
            "skill-2",
            now_ms=2_200,
        )
        self.assertTrue(passed["verdict"])
        self.assertFalse(failed["verdict"])
        self.assertFalse(passed["expected_output_exposed"])
        self.assertFalse(passed["seal_nonce_exposed"])
        self.assertNotIn("expected_output", passed)
        self.assertNotIn("expected_output_sha256", passed)
        self.assertNotIn("seal_nonce", passed)

    def test_exact_json_verifier_is_canonical_not_key_order_sensitive(self) -> None:
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

    def test_identity_binding_rejects_another_jade(self) -> None:
        with self.assertRaisesRegex(ValueError, "verifiable_task_ledger_identity_mismatch"):
            self.ledger.learning_view("other-jade", self.dataset)

    def test_conversation_memory_fields_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "task_ledger_not_conversation_memory"):
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
        self.ledger.evaluate_case(
            self.identity,
            self.dataset,
            "sealed-1",
            {"value": 17},
            "candidate_skill",
            "skill-1",
            now_ms=2_100,
        )
        self.assertTrue(self.ledger.backup_path.exists())
        self.path.write_text("{not-json", encoding="utf-8")
        status = VerifiableTaskLedger(self.path).status()
        self.assertEqual(1, status["dataset_count"])
        self.assertTrue(status["sealed_commitment_salted"])
        self.assertFalse(status["sealed_test_inputs_exposed"])
        self.assertFalse(status["sealed_test_answers_exposed"])
        self.assertFalse(status["seal_nonce_exposed"])
        self.assertFalse(status["learned_code_execution"])


if __name__ == "__main__":
    unittest.main()
