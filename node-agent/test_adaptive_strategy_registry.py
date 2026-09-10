from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from adaptive_strategy_registry import AdaptiveStrategyRegistry


class AdaptiveStrategyRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-strategy-")
        self.path = Path(self.temp.name) / "strategy.json"
        self.registry = AdaptiveStrategyRegistry(self.path)
        self.identity = "jade-test"
        self.now = 1_000_000

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def candidate(
        candidate_id: str = "nlc-1",
        rationale: str = "Tester une préférence durable uniquement après sandbox.",
        brain_profile: str = "balanced",
    ) -> dict:
        return {
            "candidate_id": candidate_id,
            "kind": "STRATEGY_HINT",
            "status": "CANDIDATE",
            "title": "Challenger de stratégie fondé sur les outcomes",
            "rationale": rationale,
            "experiment_id": f"exp-{candidate_id}",
            "target": "routing.profile_model_preference",
            "scope": {
                "task_kind": "brain_chat",
                "brain_profile": brain_profile,
                "model": "model-a",
                "node_id": "vps-a",
            },
            "sandbox_required": True,
            "automatic_activation": False,
            "automatic_promotion": False,
            "production_code_change": False,
        }

    def ingest(self, candidate: dict | None = None, revision: int = 10) -> dict:
        return self.registry.ingest_candidates(
            identity_id=self.identity,
            candidates=[candidate or self.candidate()],
            source_revision=revision,
            now_ms=self.now + revision,
        )

    def only_entry(self) -> dict:
        snapshot = self.registry.snapshot(self.identity)
        self.assertEqual(1, snapshot["strategy_count"])
        return snapshot["entries"][0]

    def test_safe_strategy_hint_is_persisted_and_survives_restart(self) -> None:
        result = self.ingest()
        self.assertEqual(1, result["accepted"])
        self.assertEqual(1, result["created"])
        self.assertEqual(0, result["reinforced"])
        self.assertFalse(result["automatic_activation"])
        self.assertFalse(result["automatic_promotion"])

        entry = self.only_entry()
        self.assertEqual("CANDIDATE", entry["status"])
        self.assertEqual("routing.profile_model_preference", entry["target"])
        self.assertEqual("balanced", entry["scope"]["brain_profile"])
        self.assertEqual(1, entry["evidence_count"])
        self.assertFalse(entry["automatic_activation"])
        self.assertFalse(entry["automatic_promotion"])
        self.assertFalse(entry["production_code_change"])

        restarted = AdaptiveStrategyRegistry(self.path)
        persisted = restarted.snapshot(self.identity)
        self.assertEqual(1, persisted["strategy_count"])
        self.assertEqual(entry["strategy_id"], persisted["entries"][0]["strategy_id"])
        self.assertFalse(persisted["raw_conversation_text_stored"])
        self.assertFalse(persisted["model_weight_mutation"])
        self.assertFalse(persisted["shell_execution"])

    def test_repeated_logical_strategy_reinforces_without_duplicate_entry(self) -> None:
        first = self.ingest(self.candidate("nlc-1"), revision=10)
        second = self.ingest(self.candidate("nlc-2"), revision=11)
        duplicate = self.ingest(self.candidate("nlc-2"), revision=11)

        self.assertEqual(1, first["created"])
        self.assertEqual(1, second["reinforced"])
        self.assertEqual(0, duplicate["created"])
        self.assertEqual(0, duplicate["reinforced"])

        entry = self.only_entry()
        self.assertEqual(2, entry["evidence_count"])
        self.assertEqual(2, len(entry["provenance"]))
        self.assertEqual("nlc-2", entry["provenance"][0]["candidate_id"])

    def test_dangerous_or_wrong_target_candidates_are_ignored(self) -> None:
        dangerous = self.candidate("danger")
        dangerous["automatic_promotion"] = True
        wrong_target = self.candidate("wrong")
        wrong_target["target"] = "routing.history_failure_penalty"
        no_sandbox = self.candidate("unsafe")
        no_sandbox["sandbox_required"] = False
        config_hint = self.candidate("config")
        config_hint["kind"] = "CONFIG_HINT"

        result = self.registry.ingest_candidates(
            identity_id=self.identity,
            candidates=[dangerous, wrong_target, no_sandbox, config_hint],
            source_revision=12,
            now_ms=self.now,
        )
        self.assertEqual(0, result["accepted"])
        self.assertEqual(4, result["ignored"])
        self.assertEqual(0, self.registry.snapshot(self.identity)["strategy_count"])

    def test_identity_binding_rejects_another_jade_identity(self) -> None:
        self.ingest()
        with self.assertRaisesRegex(ValueError, "strategy_registry_identity_mismatch"):
            self.registry.ingest_candidates(
                identity_id="other-jade",
                candidates=[self.candidate("other")],
                source_revision=20,
                now_ms=self.now,
            )

    def test_sandbox_evaluation_requires_real_delta_confidence_and_no_protected_failure(self) -> None:
        self.ingest()
        strategy_id = self.only_entry()["strategy_id"]
        testing = self.registry.begin_testing(self.identity, strategy_id, now_ms=self.now + 20)
        self.assertEqual("TESTING", testing["status"])

        too_small = self.registry.record_sandbox_evaluation(
            identity_id=self.identity,
            strategy_id=strategy_id,
            champion_score=0.10,
            challenger_score=0.30,
            confidence=0.95,
            samples=8,
            protected_failures=0,
            now_ms=self.now + 30,
        )
        self.assertEqual("TESTING", too_small["status"])

        protected_failure = self.registry.record_sandbox_evaluation(
            identity_id=self.identity,
            strategy_id=strategy_id,
            champion_score=0.10,
            challenger_score=0.50,
            confidence=0.95,
            samples=8,
            protected_failures=1,
            now_ms=self.now + 40,
        )
        self.assertEqual("TESTING", protected_failure["status"])

        validated = self.registry.record_sandbox_evaluation(
            identity_id=self.identity,
            strategy_id=strategy_id,
            champion_score=0.10,
            challenger_score=0.50,
            confidence=0.90,
            samples=8,
            protected_failures=0,
            now_ms=self.now + 50,
        )
        self.assertEqual("VALIDATED", validated["status"])
        self.assertAlmostEqual(0.40, validated["score"])
        self.assertEqual(8, validated["sandbox_samples"])
        self.assertEqual(0, validated["protected_failures"])

    def test_promotion_requires_explicit_approval_and_validation(self) -> None:
        self.ingest()
        strategy_id = self.only_entry()["strategy_id"]

        with self.assertRaisesRegex(PermissionError, "explicit_strategy_promotion_approval_required"):
            self.registry.promote(
                self.identity,
                strategy_id,
                explicit_approval=False,
                now_ms=self.now + 60,
            )

        with self.assertRaisesRegex(ValueError, "strategy_not_validated"):
            self.registry.promote(
                self.identity,
                strategy_id,
                explicit_approval=True,
                now_ms=self.now + 61,
            )

        self.registry.record_sandbox_evaluation(
            self.identity,
            strategy_id,
            champion_score=0.0,
            challenger_score=0.4,
            confidence=0.9,
            samples=8,
            protected_failures=0,
            now_ms=self.now + 62,
        )
        active = self.registry.promote(
            self.identity,
            strategy_id,
            explicit_approval=True,
            now_ms=self.now + 63,
        )
        self.assertEqual("ACTIVE", active["status"])
        self.assertFalse(active["automatic_activation"])
        self.assertFalse(active["automatic_promotion"])
        snapshot = self.registry.snapshot(self.identity)
        self.assertEqual(1, snapshot["active_strategy_count"])

    def test_successor_promotion_and_rollback_restores_previous_active_strategy(self) -> None:
        self.ingest(self.candidate("nlc-a", rationale="Préférence A"), revision=30)
        first_id = self.only_entry()["strategy_id"]
        self.registry.record_sandbox_evaluation(
            self.identity,
            first_id,
            champion_score=0.0,
            challenger_score=0.5,
            confidence=0.9,
            samples=10,
            now_ms=self.now + 30,
        )
        self.registry.promote(
            self.identity,
            first_id,
            explicit_approval=True,
            now_ms=self.now + 31,
        )

        self.ingest(self.candidate("nlc-b", rationale="Préférence B"), revision=31)
        entries = self.registry.snapshot(self.identity)["entries"]
        second_id = next(item["strategy_id"] for item in entries if item["strategy_id"] != first_id)
        self.registry.record_sandbox_evaluation(
            self.identity,
            second_id,
            champion_score=0.0,
            challenger_score=0.6,
            confidence=0.95,
            samples=10,
            now_ms=self.now + 32,
        )
        second_active = self.registry.promote(
            self.identity,
            second_id,
            explicit_approval=True,
            now_ms=self.now + 33,
        )
        self.assertEqual(first_id, second_active["previous_active_strategy_id"])

        before = {item["strategy_id"]: item for item in self.registry.snapshot(self.identity)["entries"]}
        self.assertEqual("RETIRED", before[first_id]["status"])
        self.assertEqual("ACTIVE", before[second_id]["status"])

        with self.assertRaisesRegex(PermissionError, "explicit_strategy_rollback_approval_required"):
            self.registry.rollback(
                self.identity,
                second_id,
                explicit_approval=False,
                now_ms=self.now + 34,
            )

        rolled = self.registry.rollback(
            self.identity,
            second_id,
            explicit_approval=True,
            now_ms=self.now + 35,
        )
        self.assertEqual(second_id, rolled["rolled_back_strategy_id"])
        self.assertEqual(first_id, rolled["restored_strategy_id"])

        after = {item["strategy_id"]: item for item in self.registry.snapshot(self.identity)["entries"]}
        self.assertEqual("ACTIVE", after[first_id]["status"])
        self.assertEqual("ROLLED_BACK", after[second_id]["status"])

    def test_backup_recovers_from_corrupt_primary(self) -> None:
        self.ingest(self.candidate("nlc-a", rationale="A"), revision=40)
        self.ingest(self.candidate("nlc-b", rationale="B"), revision=41)
        self.assertTrue(self.path.with_suffix(".json.bak").exists())
        self.path.write_text("{broken", encoding="utf-8")

        recovered = AdaptiveStrategyRegistry(self.path).snapshot(self.identity)
        self.assertGreaterEqual(recovered["strategy_count"], 1)
        parsed = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(1, parsed["schema_version"])


if __name__ == "__main__":
    unittest.main()
