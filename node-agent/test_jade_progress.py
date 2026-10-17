"""Offline, synthetic, non-mutating tests for jade_progress V0."""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from jade_progress import DAY_MS, generate_report, format_text

NOW = 200 * DAY_MS
THIS = NOW - 7 * DAY_MS
PREV = NOW - 14 * DAY_MS
PRIVATE_CANARY = "SEALED_TEST_PRIVATE_PAYLOAD_DO_NOT_LEAK"
TOKEN_CANARY = "SENSITIVE_NODE_TOKEN_DO_NOT_LEAK"


class ProgressReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, obj):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")

    def jsonl(self, name, rows):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8"
        )

    def fixture(self):
        self.write("workshop/skill-learning-goals.json", {
            "schema_version": 1,
            "goals": {
                "c": {"state": "RETAINED", "created_at": THIS + 10,
                      "updated_at": THIS + 200, "candidate_count": 2,
                      "reason": PRIVATE_CANARY},
                "p": {"state": "SEALED_FAILED", "created_at": PREV + 10,
                      "updated_at": PREV + 200, "candidate_count": 1},
            },
        })
        self.write("archive/verifiable-task-ledger.json", {
            "schema_version": 1,
            "datasets": {
                "c": {"sealed_exam": {"evaluated_at": THIS + 201, "verdict": True},
                      "cases": {"hidden": {"input": PRIVATE_CANARY}}},
                "p": {"sealed_exam": {"evaluated_at": PREV + 201, "verdict": False}},
            },
        })
        self.jsonl("archive/skill-attribution.jsonl", [
            {"occurred_at": THIS + 202, "event_kind": "skill_retained",
             "payload": {"secret": PRIVATE_CANARY, "token": TOKEN_CANARY}},
            {"occurred_at": THIS + 203, "event_kind": "skill_used",
             "payload": {"secret": PRIVATE_CANARY}},
        ])
        self.write("adaptive-strategy-registry.json", {
            "schema_version": 1,
            "history": [
                {"action": "INGESTED", "at": THIS + 15},
                {"action": "VALIDATED", "at": THIS + 30},
                {"action": "PROMOTED", "at": THIS + 35,
                 "previous_active_strategy_id": "old"},
                {"action": "REINFORCED", "at": THIS + 55},
            ],
            "entries": {"s": {"status": "ACTIVE", "title": PRIVATE_CANARY}},
        })
        self.write("night-cycle-supervisor.json", {
            "schema_version": 1,
            "runs": [
                {"status": "SUCCESS", "started_at": THIS + 100, "steps": []},
                {"status": "PARTIAL", "started_at": THIS + 300,
                 "steps": [
                     {"phase": "MEMORY_REVIEW", "success": False,
                      "summary": PRIVATE_CANARY},
                     {"phase": "TARGETED_RESEARCH", "success": True},
                 ]},
                {"status": "SKIPPED", "started_at": THIS + 400,
                 "reason": "pixel_active"},
                {"status": "SUCCESS", "started_at": PREV + 100, "steps": []},
            ],
        })
        self.write("jade-halt.json", {
            "schema_version": 1, "state": "RUNNING", "reason": TOKEN_CANARY,
            "source": "MANUAL", "at_ms": THIS + 30,
            "sealed_access_violations": 0, "consecutive_protected_failures": 0,
        })
        self.jsonl("jade-halt.jsonl", [
            {"kind": "halt_transition", "at_ms": THIS + 100, "reason": TOKEN_CANARY},
            {"kind": "network_guard_violation", "at_ms": THIS + 200,
             "step": PRIVATE_CANARY},
            {"kind": "halt_interrupted", "at_ms": THIS + 300,
             "loop": "adaptive_vps_night_cycle"},
        ])

    def test_complete_weekly_counts_and_delta(self):
        self.fixture()
        report = generate_report(self.root, now_ms=NOW)
        current = report["current"]["metrics"]
        previous = report["previous"]["metrics"]
        self.assertEqual(report["schema_version"], 1)
        self.assertEqual(report["halt_state"], "RUNNING")
        self.assertEqual(current["skills"], {
            "goals_opened": 1, "candidates_proposed": 2,
            "sealed_passed": 1, "sealed_failed": 0,
            "skills_retained": 1, "teacher_calls": None,
        })
        self.assertEqual(previous["skills"]["candidates_proposed"], 1)
        self.assertEqual(report["delta"]["skills"]["candidates_proposed"], 1)
        self.assertEqual(current["strategies"], {
            "candidates": 1, "validated": 1, "activated": 1,
            "retired": 1, "rolled_back": 0,
        })
        self.assertEqual(current["cycles"]["launched"], 2)
        self.assertEqual(current["cycles"]["succeeded"], 1)
        self.assertEqual(current["cycles"]["partial"], 1)
        self.assertEqual(current["cycles"]["skipped"], 1)
        self.assertEqual(current["cycles"]["skip_reasons"], {"pixel_active": 1})
        self.assertEqual(current["cycles"]["interrupted_by_halt"], 1)
        self.assertEqual(current["cycles"]["failed_phases"], {"MEMORY_REVIEW": 1})
        self.assertEqual(current["security"]["halt_transitions"], 1)
        self.assertEqual(current["security"]["network_guard_violations"], 1)
        self.assertEqual(report["delta"]["cycles"]["launched"], 1)

    def test_missing_all_stores_means_unknown_not_zero(self):
        report = generate_report(self.root, now_ms=NOW)
        current = report["current"]["metrics"]
        self.assertIsNone(current["skills"]["sealed_passed"])
        self.assertIsNone(current["strategies"]["candidates"])
        self.assertIsNone(current["cycles"]["launched"])
        self.assertIsNone(current["cycles"]["skipped"])
        self.assertIsNone(current["cycles"]["skip_reasons"])
        self.assertIsNone(current["security"]["halt_transitions"])
        self.assertEqual(report["halt_state"], "RUNNING")
        self.assertIsNone(report["delta"]["skills"]["teacher_calls"])
        self.assertFalse(list(self.root.iterdir()))

    def test_corrupt_store_and_jsonl_cannot_crash_or_invent_zero(self):
        self.write("workshop/skill-learning-goals.json", [])
        (self.root / "jade-halt.jsonl").write_bytes(b"{bad json\n")
        report = generate_report(self.root, now_ms=NOW)
        self.assertIsNone(report["current"]["metrics"]["skills"]["goals_opened"])
        self.assertIsNone(report["current"]["metrics"]["security"]["network_guard_violations"])
        self.assertTrue(any(item.startswith("invalid_schema:") for item in report["warnings"]))
        self.assertTrue(any(item.startswith("unreadable:") for item in report["warnings"]))

    def test_overlapping_goal_is_unknown_instead_of_wrong_week(self):
        self.write("workshop/skill-learning-goals.json", {
            "schema_version": 1,
            "goals": {"crossing": {
                "created_at": PREV + 10, "updated_at": THIS + 10,
                "candidate_count": 5,
            }},
        })
        result = generate_report(self.root, now_ms=NOW)
        self.assertIsNone(result["current"]["metrics"]["skills"]["candidates_proposed"])
        self.assertIsNone(result["current"]["metrics"]["skills"]["teacher_calls"])
        self.assertIsNone(result["previous"]["metrics"]["skills"]["teacher_calls"])
        self.assertIn("weekly_candidate_attempts_not_reconstructible", result["warnings"])

    def test_no_file_write_and_no_secret_in_text_or_json(self):
        self.fixture()
        def fingerprints():
            return {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in self.root.rglob("*") if p.is_file()}
        before = fingerprints()
        report = generate_report(self.root, now_ms=NOW)
        after = fingerprints()
        self.assertEqual(before, after)
        combined = json.dumps(report, ensure_ascii=False) + format_text(report)
        self.assertNotIn(PRIVATE_CANARY, combined)
        self.assertNotIn(TOKEN_CANARY, combined)
        self.assertNotIn("case", report["current"]["metrics"]["skills"])

    def test_json_cli_is_versioned_with_only_missing_warnings(self):
        environment = {**os.environ, "JADE_GENESIS_CONFIG_DIR": str(self.root)}
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("jade_progress.py")),
             "report", "--days", "7", "--json"],
            capture_output=True, text=True, env=environment, check=True
        )
        obj = json.loads(result.stdout)
        self.assertEqual(obj["schema_version"], 1)
        self.assertEqual(obj["days"], 7)
        self.assertEqual(obj["halt_state"], "RUNNING")
        self.assertEqual(result.stderr, "")

    def test_nested_store_shapes_fail_closed(self):
        self.write("workshop/skill-learning-goals.json", {
            "schema_version": 1, "goals": []
        })
        self.write("archive/verifiable-task-ledger.json", {
            "schema_version": 1, "datasets": []
        })
        report = generate_report(self.root, now_ms=NOW)
        self.assertIsNone(report["current"]["metrics"]["skills"]["goals_opened"])
        self.assertIsNone(report["current"]["metrics"]["skills"]["sealed_passed"])
        self.assertIn("invalid_shape:workshop/skill-learning-goals.json", report["warnings"])
        self.assertIn("invalid_shape:archive/verifiable-task-ledger.json", report["warnings"])

    def test_malformed_phase_is_ignored_without_crashing(self):
        self.write("night-cycle-supervisor.json", {
            "schema_version": 1, "runs": [
                {"status": "PARTIAL", "started_at": THIS + 100,
                 "steps": [{"phase": ["unhashable"], "success": False}]}
            ]
        })
        report = generate_report(self.root, now_ms=NOW)
        self.assertEqual(report["current"]["metrics"]["cycles"]["failed_phases"], {})
        self.assertEqual(report["current"]["metrics"]["cycles"]["partial"], 1)
        self.assertIsNone(report["current"]["metrics"]["cycles"]["skipped"])

    def test_invalid_days_fail_closed(self):
        with self.assertRaises(ValueError):
            generate_report(self.root, days=0, now_ms=NOW)
        with self.assertRaises(ValueError):
            generate_report(self.root, days=366, now_ms=NOW)

    @staticmethod
    def valid_halt(state="HALTED"):
        return {"schema_version": 1, "state": state, "reason": "synthetic",
                "source": "MANUAL", "at_ms": 10,
                "sealed_access_violations": 0, "consecutive_protected_failures": 0}

    def assert_halt_parity_readonly(self, raw, expected, *, unreadable=False):
        # The oracle may journal corrupt state, so give it a separate directory.
        from jade_halt import JadeHalt
        with tempfile.TemporaryDirectory() as case_dir, tempfile.TemporaryDirectory() as oracle_dir:
            case, oracle = Path(case_dir), Path(oracle_dir)
            for directory in (case, oracle):
                path = directory / "jade-halt.json"
                if unreadable:
                    path.mkdir()  # read_text raises OSError on every platform.
                elif raw is not None:
                    path.write_bytes(raw)
                (directory / "jade-halt.jsonl").write_text(
                    '{"kind":"existing_synthetic_event","at_ms":1}\n', encoding="utf-8")
                (directory / "jade-halt.json.bak").write_text(
                    json.dumps(self.valid_halt("HALTED")), encoding="utf-8")
            actual_oracle = JadeHalt(oracle / "jade-halt.json")._load()["state"]
            self.assertEqual(actual_oracle, expected)
            def snapshot():
                return {str(p.relative_to(case)): hashlib.sha256(p.read_bytes()).hexdigest()
                        if p.is_file() else "directory" for p in case.rglob("*")}
            before = snapshot()
            with patch.object(JadeHalt, "status", side_effect=AssertionError("Writer called")), \
                    patch.object(JadeHalt, "_load", side_effect=AssertionError("Loader called")):
                report = generate_report(case, now_ms=NOW)
            self.assertEqual(report["halt_state"], expected)
            self.assertEqual(before, snapshot())
            return report

    def test_halt_absent_matches_running_without_creating_files(self):
        report = self.assert_halt_parity_readonly(None, "RUNNING")
        self.assertNotIn("halt_state_unreadable_fail_closed", report["warnings"])

    def test_halt_unreadable_matches_fail_closed_and_preserves_journal(self):
        for raw in (b"{bad json", b"\xff\xfe"):
            with self.subTest(raw=raw):
                report = self.assert_halt_parity_readonly(raw, "HALTED")
                self.assertIn("halt_state_unreadable_fail_closed", report["warnings"])
                self.assertIn("HALTED (état illisible, fail-closed)", format_text(report))
        report = self.assert_halt_parity_readonly(None, "HALTED", unreadable=True)
        self.assertIn("halt_state_unreadable_fail_closed", report["warnings"])

    def test_halt_invalid_schema_or_fields_matches_fail_closed(self):
        cases = [[], {"schema_version": 1, "state": "RUNNING"}]
        for field, value in (("schema_version", 2), ("state", "UNKNOWN"),
                             ("reason", None), ("source", 7)):
            cases.append({**self.valid_halt("RUNNING"), field: value})
        for field in ("reason", "source", "at_ms", "sealed_access_violations",
                      "consecutive_protected_failures"):
            missing = self.valid_halt("RUNNING"); del missing[field]; cases.append(missing)
        for field in ("at_ms", "sealed_access_violations", "consecutive_protected_failures"):
            for value in (-1, "0", 1.5, None):
                cases.append({**self.valid_halt("RUNNING"), field: value})
        for value in cases:
            with self.subTest(value=value):
                report = self.assert_halt_parity_readonly(json.dumps(value).encode(), "HALTED")
                self.assertIn("halt_state_unreadable_fail_closed", report["warnings"])

    def test_halt_valid_halted_matches_runtime_without_writing(self):
        report = self.assert_halt_parity_readonly(json.dumps(self.valid_halt()).encode(), "HALTED")
        self.assertNotIn("halt_state_unreadable_fail_closed", report["warnings"])

    def test_teacher_calls_is_unknown_without_real_counter(self):
        self.fixture()
        report = generate_report(self.root, now_ms=NOW)
        self.assertIsNone(report["current"]["metrics"]["skills"]["teacher_calls"])
        self.assertIsNone(report["previous"]["metrics"]["skills"]["teacher_calls"])
        self.assertIsNone(report["delta"]["skills"]["teacher_calls"])
        self.assertIn("teacher_calls_not_recorded", report["warnings"])


if __name__ == "__main__":
    unittest.main()
