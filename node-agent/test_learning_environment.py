from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from case_pack_archive import archive_sealed_case_pack, verify_case_pack
from learning_environment import (
    append_attribution_event,
    attribution_summary,
    read_attribution_events,
)
from verifiable_task_ledger import VerifiableTaskLedger


class LearningEnvironmentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-learning-env-")
        self.root = Path(self.temp.name)
        self.ledger = VerifiableTaskLedger(self.root / "archive" / "ledger.json")
        self.identity = "jade-test"
        self.dataset = "case-pack-v1"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _sealed_dataset(self) -> None:
        self.ledger.create_dataset(
            self.identity,
            self.dataset,
            "deterministic_transform",
            now_ms=100,
        )
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "train-1",
            "TRAIN",
            {"x": 1},
            {"value": 2},
            now_ms=110,
        )
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "validation-1",
            "VALIDATION",
            {"x": 3},
            {"value": 4},
            now_ms=120,
        )
        self.ledger.add_case(
            self.identity,
            self.dataset,
            "sealed-1",
            "SEALED_TEST",
            {"x": 907},
            {"value": 908},
            now_ms=130,
        )
        self.ledger.seal_dataset(self.identity, self.dataset, now_ms=140)

    def test_sealed_case_pack_is_write_once_and_contains_full_truth(self) -> None:
        self._sealed_dataset()
        pack_dir = self.root / "archive" / "case-packs"
        first = archive_sealed_case_pack(
            self.ledger,
            self.identity,
            self.dataset,
            archive_dir=pack_dir,
        )
        second = archive_sealed_case_pack(
            self.ledger,
            self.identity,
            self.dataset,
            archive_dir=pack_dir,
        )

        self.assertFalse(first["replayed"])
        self.assertTrue(second["replayed"])
        self.assertEqual(first["case_pack_sha256"], second["case_pack_sha256"])
        self.assertEqual(3, first["case_count"])
        self.assertEqual(1, first["sealed_test_count"])
        self.assertTrue(first["immutable"])
        self.assertFalse(first["path_exposed_to_teacher"])
        self.assertFalse(first["sealed_test_inputs_exposed"])
        self.assertFalse(first["sealed_test_answers_exposed"])
        self.assertFalse(first["seal_nonce_exposed"])

        files = list(pack_dir.glob("*.case-pack.json"))
        self.assertEqual(1, len(files))
        trusted = verify_case_pack(files[0])
        sealed = [
            case for case in trusted["cases"] if case["partition"] == "SEALED_TEST"
        ]
        self.assertEqual(1, len(sealed))
        self.assertEqual({"x": 907}, sealed[0]["input"])
        self.assertEqual({"value": 908}, sealed[0]["expected_output"])
        self.assertTrue(trusted["seal_nonce"])

        learning = self.ledger.learning_view(self.identity, self.dataset)
        self.assertNotIn(
            "sealed-1",
            {case["case_id"] for case in learning["cases"]},
        )
        self.assertNotIn(
            {"x": 907},
            [case["input"] for case in learning["cases"]],
        )

    def test_case_pack_tamper_is_detected(self) -> None:
        self._sealed_dataset()
        pack_dir = self.root / "archive" / "case-packs"
        manifest = archive_sealed_case_pack(
            self.ledger,
            self.identity,
            self.dataset,
            archive_dir=pack_dir,
        )
        target = pack_dir / manifest["archive_filename"]
        original = json.loads(target.read_text(encoding="utf-8"))
        original["task_family"] = "tampered-family"
        target.chmod(0o600)
        target.write_text(json.dumps(original), encoding="utf-8")

        with self.assertRaisesRegex(RuntimeError, "case_pack_hash_mismatch"):
            verify_case_pack(target)
        with self.assertRaisesRegex(RuntimeError, "case_pack_immutable_conflict"):
            archive_sealed_case_pack(
                self.ledger,
                self.identity,
                self.dataset,
                archive_dir=pack_dir,
            )

    def test_attribution_log_is_hash_chained_and_usage_is_derived(self) -> None:
        log = self.root / "archive" / "skill-attribution.jsonl"
        retained = append_attribution_event(
            "skill_retained",
            {
                "skill_id": "learned-sum",
                "skill_version": 1,
                "provenance": {
                    "source_kind": "EXTERNAL_TEACHER",
                    "source_id": "teacher-a",
                },
                "measured_gain": {
                    "metric": "success_rate",
                    "before": 0.0,
                    "after": 1.0,
                    "delta": 1.0,
                },
            },
            path=log,
            now_ms=1_000,
        )
        first_use = append_attribution_event(
            "skill_used",
            {"skill_id": "learned-sum", "skill_version": 1},
            path=log,
            now_ms=1_100,
        )
        second_use = append_attribution_event(
            "skill_used",
            {"skill_id": "learned-sum", "skill_version": 1},
            path=log,
            now_ms=1_200,
        )

        self.assertEqual("0" * 64, retained["previous_sha256"])
        self.assertEqual(retained["event_sha256"], first_use["previous_sha256"])
        self.assertEqual(first_use["event_sha256"], second_use["previous_sha256"])
        self.assertEqual(3, len(read_attribution_events(path=log)))

        summary = attribution_summary("learned-sum", 1, path=log)
        self.assertEqual(2, summary["usage_count"])
        self.assertEqual("EXTERNAL_TEACHER", summary["provenance"]["source_kind"])
        self.assertEqual(1.0, summary["measured_gain"]["delta"])
        self.assertEqual(3, summary["event_count"])

    def test_attribution_tamper_breaks_chain_verification(self) -> None:
        log = self.root / "archive" / "skill-attribution.jsonl"
        append_attribution_event(
            "skill_retained",
            {"skill_id": "skill-a", "skill_version": 1},
            path=log,
            now_ms=1_000,
        )
        append_attribution_event(
            "skill_used",
            {"skill_id": "skill-a", "skill_version": 1},
            path=log,
            now_ms=1_100,
        )

        lines = log.read_text(encoding="utf-8").splitlines()
        first = json.loads(lines[0])
        first["payload"]["skill_id"] = "tampered"
        lines[0] = json.dumps(first, separators=(",", ":"), sort_keys=True)
        log.write_text("\n".join(lines) + "\n", encoding="utf-8")

        with self.assertRaisesRegex(
            RuntimeError,
            "attribution_archive_hash_mismatch",
        ):
            read_attribution_events(path=log)


if __name__ == "__main__":
    unittest.main()
