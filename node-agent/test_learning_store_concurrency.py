from __future__ import annotations

import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import first_learning_family as family
import learning_stores
from learning_stores import ArchiveVerifiableTaskLedger


class LearningStoreConcurrencyTest(unittest.TestCase):
    def test_reopened_ledgers_share_one_lock_and_preserve_all_writes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ledger.json"
            identity = "identity-concurrency"
            dataset = "concurrent-dataset"
            first = ArchiveVerifiableTaskLedger(path)
            second = ArchiveVerifiableTaskLedger(path)
            self.assertIs(first.lock, second.lock)
            first.create_dataset(identity, dataset, "concurrency_family", now_ms=1)

            count = 40
            barrier = threading.Barrier(count)

            def write_case(index: int) -> dict:
                ledger = ArchiveVerifiableTaskLedger(path)
                barrier.wait(timeout=10)
                return ledger.add_case(
                    identity,
                    dataset,
                    f"case-{index:02d}",
                    "TRAIN",
                    {"value": index},
                    {"value": index},
                    source="concurrency_test",
                    now_ms=100 + index,
                )

            with ThreadPoolExecutor(max_workers=count) as executor:
                results = list(executor.map(write_case, range(count)))

            self.assertEqual(count, len(results))
            ledger = ArchiveVerifiableTaskLedger(path)
            with ledger.lock:
                state = ledger._load()
                cases = state["datasets"][dataset]["cases"]
                self.assertEqual(count, len(cases))
                self.assertEqual(
                    {f"case-{index:02d}" for index in range(count)},
                    set(cases),
                )
                self.assertEqual(count + 1, state["revision"])

            leftovers = list(path.parent.glob(f".{path.name}.*.tmp"))
            self.assertEqual([], leftovers)

    def test_record_real_case_is_atomic_across_reopened_ledgers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "real-ledger.json"
            identity = "identity-real-concurrency"
            count = 40
            barrier = threading.Barrier(count)

            def open_test_ledger() -> ArchiveVerifiableTaskLedger:
                return ArchiveVerifiableTaskLedger(path)

            def fake_case_pack(*args, **kwargs) -> dict:
                return {"case_pack_sha256": "f" * 64}

            def enroll(index: int) -> dict:
                barrier.wait(timeout=10)
                return family.record_real_case(
                    identity,
                    {"text": f"  Concurrent-{index:02d}  "},
                    now_ms=1_000 + index,
                )

            with (
                patch.object(family, "open_archive_ledger", side_effect=open_test_ledger),
                patch.object(family, "_append_sealed_event_once", return_value={}),
                patch.object(learning_stores, "archive_sealed_case_pack", side_effect=fake_case_pack),
            ):
                with ThreadPoolExecutor(max_workers=count) as executor:
                    results = list(executor.map(enroll, range(count)))

            self.assertTrue(all(result["recorded"] is True for result in results))
            ledger = ArchiveVerifiableTaskLedger(path)
            with ledger.lock:
                state = ledger._load()
                datasets = [
                    item
                    for item in state["datasets"].values()
                    if item.get("task_family") == family.TASK_FAMILY
                ]
                self.assertEqual(8, len(datasets))
                total_cases = 0
                seen_inputs: set[str] = set()
                for dataset in datasets:
                    self.assertTrue(dataset["sealed"])
                    self.assertEqual(5, len(dataset["cases"]))
                    self.assertEqual(
                        {"real-01", "real-02", "real-03", "real-04", "real-05"},
                        set(dataset["cases"]),
                    )
                    ordered = [dataset["cases"][f"real-{position:02d}"] for position in range(1, 6)]
                    self.assertEqual(
                        list(family.PARTITION_PLAN),
                        [case["partition"] for case in ordered],
                    )
                    self.assertEqual(64, len(dataset["sealed_set_sha256"]))
                    for case in ordered:
                        text = case["input"]["text"]
                        self.assertNotIn(text, seen_inputs)
                        seen_inputs.add(text)
                        total_cases += 1
                self.assertEqual(count, total_cases)
                self.assertEqual(count, len(seen_inputs))

    def test_simultaneous_duplicate_real_input_is_recorded_once(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "duplicate-ledger.json"
            identity = "identity-duplicate-concurrency"
            count = 20
            barrier = threading.Barrier(count)

            def open_test_ledger() -> ArchiveVerifiableTaskLedger:
                return ArchiveVerifiableTaskLedger(path)

            def enroll(_: int) -> dict:
                barrier.wait(timeout=10)
                return family.record_real_case(
                    identity,
                    {"text": "  Same-Input  "},
                    now_ms=2_000,
                )

            with (
                patch.object(family, "open_archive_ledger", side_effect=open_test_ledger),
                patch.object(family, "_append_sealed_event_once", return_value={}),
            ):
                with ThreadPoolExecutor(max_workers=count) as executor:
                    results = list(executor.map(enroll, range(count)))

            recorded = [result for result in results if result["recorded"] is True]
            duplicates = [result for result in results if result["recorded"] is False]
            self.assertEqual(1, len(recorded))
            self.assertEqual(count - 1, len(duplicates))
            self.assertTrue(
                all(result.get("reason") == "duplicate_real_input" for result in duplicates)
            )

            ledger = ArchiveVerifiableTaskLedger(path)
            with ledger.lock:
                state = ledger._load()
                datasets = list(state["datasets"].values())
                self.assertEqual(1, len(datasets))
                self.assertEqual(1, len(datasets[0]["cases"]))

    def test_sealed_dataset_artifacts_are_reconciled_after_pack_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "artifact-recovery-ledger.json"
            identity = "identity-artifact-recovery"

            def open_test_ledger() -> ArchiveVerifiableTaskLedger:
                return ArchiveVerifiableTaskLedger(path)

            def fake_case_pack(*args, **kwargs) -> dict:
                return {"case_pack_sha256": "f" * 64}

            with (
                patch.object(family, "open_archive_ledger", side_effect=open_test_ledger),
                patch.object(family, "_append_sealed_event_once", return_value={}),
                patch.object(learning_stores, "archive_sealed_case_pack", side_effect=fake_case_pack),
            ):
                for index in range(4):
                    result = family.record_real_case(
                        identity,
                        {"text": f" Value-{index} "},
                        now_ms=3_000 + index,
                    )
                    self.assertTrue(result["recorded"])
                    self.assertFalse(result["dataset_sealed"])

            with (
                patch.object(family, "open_archive_ledger", side_effect=open_test_ledger),
                patch.object(family, "_append_sealed_event_once", return_value={}),
                patch.object(
                    learning_stores,
                    "archive_sealed_case_pack",
                    side_effect=OSError("case-pack publish interrupted"),
                ),
            ):
                with self.assertRaisesRegex(OSError, "case-pack publish interrupted"):
                    family.record_real_case(
                        identity,
                        {"text": " Value-4 "},
                        now_ms=3_100,
                    )

            ledger = ArchiveVerifiableTaskLedger(path)
            with ledger.lock:
                state = ledger._load()
                first = state["datasets"]["normalize-label-real-0001"]
                self.assertTrue(first["sealed"])
                self.assertEqual(5, len(first["cases"]))
                self.assertEqual(64, len(first["sealed_set_sha256"]))

            with (
                patch.object(family, "open_archive_ledger", side_effect=open_test_ledger),
                patch.object(learning_stores, "archive_sealed_case_pack", side_effect=fake_case_pack) as pack,
                patch.object(family, "_append_sealed_event_once", return_value={}) as event,
            ):
                recovered = family.record_real_case(
                    identity,
                    {"text": " Value-5 "},
                    now_ms=3_200,
                )

            self.assertTrue(recovered["recorded"])
            self.assertEqual("normalize-label-real-0002", recovered["dataset_id"])
            self.assertEqual("real-01", recovered["case_id"])
            self.assertGreaterEqual(pack.call_count, 1)
            self.assertGreaterEqual(event.call_count, 1)
            payload = event.call_args_list[0].args[0]
            self.assertEqual("normalize-label-real-0001", payload["dataset_id"])
            self.assertTrue(payload["recovered_after_interruption"])

            with ledger.lock:
                final_state = ledger._load()
                self.assertEqual(2, len(final_state["datasets"]))
                self.assertTrue(
                    final_state["datasets"]["normalize-label-real-0001"]["sealed"]
                )
                self.assertEqual(
                    1,
                    len(final_state["datasets"]["normalize-label-real-0002"]["cases"]),
                )


if __name__ == "__main__":
    unittest.main()
