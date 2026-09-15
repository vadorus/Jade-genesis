from __future__ import annotations

import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import learning_stores
import skill_registry as skill_registry_module
from learning_stores import ArchiveVerifiableTaskLedger
from proof_store_hardening import HardenedLearningGoalStore, HardenedSkillRegistry
from resilient_skill_synthesis import ResilientSkillSynthesisLoop
from skill_spec import BODY_KIND
from verifiable_task_ledger import VerifiableTaskLedger


class ProofStoreHardeningTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-proof-hardening-")
        self.root = Path(self.temp.name)
        self.identity = "jade-proof-test"
        self.family = "proof_sum"
        self.dataset = "proof-dataset"

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def input_contract() -> dict:
        return {
            "type": "object",
            "required_fields": ["a", "b"],
            "description": "Two numbers.",
        }

    @staticmethod
    def output_contract() -> dict:
        return {
            "type": "object",
            "required_fields": ["value"],
            "description": "Their sum.",
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
                        {"op": "get", "args": [{"op": "literal", "args": ["a"]}]},
                        {"op": "get", "args": [{"op": "literal", "args": ["b"]}]},
                    ],
                },
            ],
        }

    def make_base_ledger(self) -> VerifiableTaskLedger:
        ledger = VerifiableTaskLedger(self.root / "ledger.json")
        ledger.create_dataset(self.identity, self.dataset, self.family, now_ms=10)
        cases = [
            ("train-1", "TRAIN", {"a": 1, "b": 2}, {"value": 3}),
            ("train-2", "TRAIN", {"a": 5, "b": 7}, {"value": 12}),
            ("validation-1", "VALIDATION", {"a": 9, "b": 4}, {"value": 13}),
            ("sealed-1", "SEALED_TEST", {"a": 101, "b": 203}, {"value": 304}),
            ("sealed-2", "SEALED_TEST", {"a": 409, "b": 503}, {"value": 912}),
        ]
        for index, (case_id, partition, input_value, expected) in enumerate(cases):
            ledger.add_case(
                self.identity,
                self.dataset,
                case_id,
                partition,
                input_value,
                expected,
                now_ms=20 + index,
            )
        ledger.seal_dataset(self.identity, self.dataset, now_ms=40)
        return ledger

    def make_hardened_registry(self) -> HardenedSkillRegistry:
        return HardenedSkillRegistry(
            self.root / "registry.json",
            route_path=self.root / "routes.json",
            attribution_path=self.root / "attribution.jsonl",
        )

    def make_goals(
        self,
        ledger: VerifiableTaskLedger,
        registry: HardenedSkillRegistry,
    ) -> HardenedLearningGoalStore:
        goals = HardenedLearningGoalStore(self.root / "goals.json")
        goals.open_gap(
            self.identity,
            "goal-proof",
            self.family,
            self.dataset,
            self.input_contract(),
            self.output_contract(),
            reason="Acquire proof sum.",
            registry=registry,
            ledger=ledger,
            now_ms=50,
        )
        return goals

    def test_transient_primary_read_error_never_restores_backup(self) -> None:
        path = self.root / "archive" / "ledger.json"
        ledger = ArchiveVerifiableTaskLedger(path)
        ledger.create_dataset(self.identity, "d1", "family", now_ms=1)
        ledger.add_case(
            self.identity,
            "d1",
            "case-1",
            "TRAIN",
            {"value": 1},
            {"value": 1},
            now_ms=2,
        )
        before = path.read_bytes()
        self.assertTrue(ledger.backup_path.exists())
        original_read_text = Path.read_text

        def fail_primary(candidate: Path, *args, **kwargs):
            if candidate == path:
                raise OSError("synthetic EMFILE")
            return original_read_text(candidate, *args, **kwargs)

        with patch.object(Path, "read_text", fail_primary):
            with self.assertRaisesRegex(RuntimeError, "verifiable_task_ledger_unavailable"):
                ledger.sealed_manifest(self.identity, "d1")

        self.assertEqual(before, path.read_bytes())
        self.assertEqual(2, ledger._load()["revision"])

    def test_factories_wire_shared_fail_closed_store_types(self) -> None:
        archive = self.root / "archive"
        workshop = self.root / "workshop"
        production = self.root / "production"
        with (
            patch.object(learning_stores, "ARCHIVE_TASK_LEDGER_PATH", archive / "ledger.json"),
            patch.object(learning_stores, "WORKSHOP_GOALS_PATH", workshop / "goals.json"),
            patch.object(learning_stores, "ARCHIVE_SKILL_REGISTRY_PATH", archive / "registry.json"),
            patch.object(learning_stores, "PRODUCTION_SKILL_ROUTES_PATH", production / "routes.json"),
            patch.object(learning_stores, "ARCHIVE_ATTRIBUTION_LOG_PATH", archive / "attribution.jsonl"),
            patch.object(learning_stores, "LEGACY_TASK_LEDGER_PATH", self.root / "missing-ledger.json"),
            patch.object(learning_stores, "LEGACY_LEARNING_GOALS_PATH", self.root / "missing-goals.json"),
        ):
            ledger_a = learning_stores.open_archive_ledger()
            ledger_b = learning_stores.open_archive_ledger()
            goals_a = learning_stores.open_workshop_goals()
            goals_b = learning_stores.open_workshop_goals()
            registry_a = learning_stores.open_archive_skill_registry()
            registry_b = learning_stores.open_archive_skill_registry()

            self.assertIs(ledger_a.lock, ledger_b.lock)
            self.assertIs(goals_a.lock, goals_b.lock)
            self.assertIs(registry_a.lock, registry_b.lock)
            self.assertIs(registry_a.routes.lock, registry_b.routes.lock)
            self.assertIs(skill_registry_module._GLOBAL_SKILL_REGISTRY, registry_b)
            self.assertIsInstance(goals_a, HardenedLearningGoalStore)
            self.assertIsInstance(registry_a, HardenedSkillRegistry)

            ledger_a.create_dataset(self.identity, "wired", "family", now_ms=1)
            if os.name != "nt":
                self.assertEqual(0o600, stat.S_IMODE(ledger_a.path.stat().st_mode))
                self.assertEqual(0o700, stat.S_IMODE(ledger_a.path.parent.stat().st_mode))

    def test_teacher_skill_id_collision_is_rejected_before_hidden_exam(self) -> None:
        ledger = self.make_base_ledger()
        registry = self.make_hardened_registry()

        developer_spec = {
            "schema_version": 1,
            "skill_id": "occupied-skill",
            "skill_version": 1,
            "task_family": "other_family",
            "domain": "verifiable_procedure",
            "description": "Occupy the versioned key.",
            "input_contract": self.input_contract(),
            "output_contract": self.output_contract(),
            "body_kind": BODY_KIND,
            "body": self.correct_body(),
            "dependencies": [],
            "provenance": {
                "source_kind": "DEVELOPER",
                "source_id": "test",
                "source_model": "",
                "created_at": 1,
            },
            "evaluation_policy": {
                "verifier_kind": "exact_json_v1",
                "sealed_set_sha256": "a" * 64,
                "min_pass_rate": 1.0,
                "max_protected_failures": 0,
            },
        }
        registry.register_developer_skill(self.identity, developer_spec, now_ms=2)
        goals = self.make_goals(ledger, registry)
        loop = ResilientSkillSynthesisLoop(ledger, registry, goals)
        requests: list[dict] = []

        def teacher(request: dict) -> dict:
            requests.append(request)
            return {
                "skill_id": "occupied-skill" if len(requests) == 1 else "fresh-skill",
                "description": "Correct body.",
                "domain": "verifiable_procedure",
                "body": self.correct_body(),
            }

        result = loop.learn(
            self.identity,
            "goal-proof",
            teacher,
            teacher_id="teacher-test",
            max_candidates=2,
            now_ms=100,
        )
        self.assertEqual("RETAINED", result["state"])
        self.assertEqual(2, len(requests))
        first_feedback = requests[1]["previous_attempts"][0]
        self.assertEqual("skill_registry_candidate_slot_occupied", first_feedback["pre_exam_rejected"])
        self.assertFalse(first_feedback["all_visible_passed"])
        exam = ledger.sealed_manifest(self.identity, self.dataset)
        self.assertTrue(exam["sealed_exam_consumed"])
        selected = registry.selected_skill(self.identity, self.family)
        self.assertEqual("fresh-skill", selected["skill_id"])

    def test_passed_exam_can_resume_exact_frozen_spec_after_retention_fault(self) -> None:
        ledger = self.make_base_ledger()
        registry = self.make_hardened_registry()
        goals = self.make_goals(ledger, registry)
        loop = ResilientSkillSynthesisLoop(ledger, registry, goals)
        teacher_calls = 0

        def teacher(_: dict) -> dict:
            nonlocal teacher_calls
            teacher_calls += 1
            return {
                "skill_id": "recoverable-skill",
                "description": "Correct body.",
                "domain": "verifiable_procedure",
                "body": self.correct_body(),
            }

        original_register = registry.register_verified_skill
        with patch.object(
            registry,
            "register_verified_skill",
            side_effect=RuntimeError("synthetic retention fault"),
        ):
            with self.assertRaisesRegex(RuntimeError, "synthetic retention fault"):
                loop.learn(
                    self.identity,
                    "goal-proof",
                    teacher,
                    teacher_id="teacher-test",
                    max_candidates=1,
                    now_ms=200,
                )

        frozen_goal = goals.get(self.identity, "goal-proof")
        self.assertEqual("FROZEN", frozen_goal["state"])
        self.assertIsInstance(frozen_goal.get("frozen_spec"), dict)
        self.assertEqual(
            frozen_goal["selected_spec_sha256"],
            ledger.verified_exam_for_candidate(
                self.identity,
                self.dataset,
                frozen_goal["selected_spec_sha256"],
            )["candidate_spec_sha256"],
        )
        self.assertEqual(1, teacher_calls)

        # Restore the real method and reconcile. The sealed exam is replayed
        # idempotently for the same hash; the teacher is never called again.
        registry.register_verified_skill = original_register
        recovered = loop.resume_frozen(self.identity, "goal-proof", now_ms=300)
        self.assertEqual("RETAINED", recovered["state"])
        self.assertTrue(recovered["sealed_exam"]["replayed"])
        self.assertEqual(1, teacher_calls)
        self.assertEqual("RETAINED", goals.get(self.identity, "goal-proof")["state"])

    def test_visible_testing_direct_reentry_does_not_reset_budget(self) -> None:
        ledger = self.make_base_ledger()
        registry = self.make_hardened_registry()
        goals = self.make_goals(ledger, registry)
        goals.update(
            self.identity,
            "goal-proof",
            {
                "state": "VISIBLE_TESTING",
                "candidate_count": 2,
                "visible_attempts": [{"candidate_number": 1}, {"candidate_number": 2}],
            },
            now_ms=60,
        )
        loop = ResilientSkillSynthesisLoop(ledger, registry, goals)
        calls = 0

        def teacher(_: dict) -> dict:
            nonlocal calls
            calls += 1
            return {}

        with self.assertRaisesRegex(ValueError, "visible_testing_goal_requires_manual_review"):
            loop.learn(
                self.identity,
                "goal-proof",
                teacher,
                teacher_id="teacher-test",
                now_ms=70,
            )
        persisted = goals.get(self.identity, "goal-proof")
        self.assertEqual(2, persisted["candidate_count"])
        self.assertEqual(2, len(persisted["visible_attempts"]))
        self.assertEqual(0, calls)


if __name__ == "__main__":
    unittest.main()
