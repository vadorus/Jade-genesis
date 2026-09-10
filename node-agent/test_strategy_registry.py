from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from strategy_registry import (
    MIN_PROMOTION_CONFIDENCE,
    MIN_PROMOTION_SAMPLES,
    StrategyRegistry,
)


class StrategyRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-strategy-")
        self.path = Path(self.temp.name) / "strategy-registry.json"
        self.registry = StrategyRegistry(self.path)

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def learning_snapshot(
        candidate_id: str = "candidate-1",
        source_revision: int = 41,
        preferred_model: str = "model-fast",
        comparison_model: str = "model-slow",
    ) -> dict:
        experiment_id = f"experiment-{candidate_id}"
        hypothesis_id = f"hypothesis-{candidate_id}"
        return {
            "schema_version": 1,
            "run_id": f"night-{source_revision}",
            "source_revision": source_revision,
            "generated_at": 10_000 + source_revision,
            "reviewed_at": 11_000 + source_revision,
            "signals": [{
                "kind": "outcome_quality_advantage",
                "task_kind": "brain_chat",
                "brain_profile": "code",
                "node_id": "pc-node",
                "model": preferred_model,
                "samples": 9,
                "metric": 0.55,
                "outcome_confidence": 0.88,
                "preferred_node_id": "pc-node",
                "preferred_model": preferred_model,
                "comparison_node_id": "vps-node",
                "comparison_model": comparison_model,
            }],
            "hypotheses": [{
                "hypothesis_id": hypothesis_id,
                "signal_kind": "outcome_quality_advantage",
                "statement": "Le backend préféré semble meilleur sur ce profil.",
                "confidence": 0.86,
                "falsifiable_metric": "outcome_quality_score_delta",
                "success_criterion": "écart reproduit",
                "evidence_ids": [],
                "personal_outcome_evidence": True,
                "promoted_as_external_fact": False,
            }],
            "experiments": [{
                "experiment_id": experiment_id,
                "hypothesis_id": hypothesis_id,
                "target": "routing.profile_model_preference",
                "change_hint": "Comparer les deux backends sur scénarios appariés.",
                "primary_metric": "outcome_quality_score_delta",
                "minimum_samples_per_side": 5,
                "maximum_samples_per_side": 16,
                "paired_scenarios_required": True,
                "frozen_champion_required": True,
                "sandbox_required": True,
                "automatic_execution": False,
                "requires_explicit_promotion_approval": True,
            }],
            "improvement_candidates": [{
                "candidate_id": candidate_id,
                "kind": "STRATEGY_HINT",
                "status": "CANDIDATE",
                "title": "Challenger de stratégie fondé sur les outcomes",
                "rationale": "Préférence à confirmer avant promotion.",
                "experiment_id": experiment_id,
                "target": "routing.profile_model_preference",
                "sandbox_required": True,
                "automatic_activation": False,
                "automatic_promotion": False,
                "production_code_change": False,
            }],
            "automatic_experiment_execution": False,
            "automatic_promotion": False,
            "production_code_rewrite": False,
            "shell_execution": False,
            "raw_conversation_text_used": False,
            "user_feedback_promoted_to_external_fact": False,
        }

    def test_ingest_persists_identity_bound_strategy_with_provenance(self) -> None:
        result = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=20_000,
        )
        self.assertEqual(1, result["persisted_count"])
        snapshot = result["registry"]
        self.assertEqual("jade-test", snapshot["identity_id"])
        self.assertEqual(1, snapshot["entry_count"])
        self.assertEqual(1, snapshot["candidate_count"])
        self.assertFalse(snapshot["automatic_promotion"])
        self.assertFalse(snapshot["automatic_runtime_application"])
        self.assertFalse(snapshot["production_code_rewrite"])
        self.assertFalse(snapshot["shell_execution"])

        entry = snapshot["entries"][0]
        self.assertEqual("ROUTING_STRATEGY", entry["kind"])
        self.assertEqual("routing.profile_model_preference", entry["target"])
        self.assertEqual("prefer", entry["mode"])
        self.assertEqual("brain_chat", entry["task_kind"])
        self.assertEqual("code", entry["brain_profile"])
        self.assertEqual("model-fast", entry["preferred_model"])
        self.assertEqual("model-slow", entry["comparison_model"])
        self.assertEqual(41, entry["source_revision"])
        self.assertEqual("candidate-1", entry["candidate_id"])
        self.assertTrue(entry["sandbox_required"])
        self.assertTrue(entry["explicit_promotion_approval_required"])
        self.assertFalse(entry["runtime_application_enabled"])

        disk = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual("jade-test", disk["identity_id"])
        self.assertEqual(1, len(disk["entries"]))

    def test_ingest_is_idempotent_for_same_candidate(self) -> None:
        first = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=20_000,
        )
        second = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=21_000,
        )
        self.assertEqual(1, first["persisted_count"])
        self.assertEqual(0, second["persisted_count"])
        self.assertEqual(1, second["registry"]["entry_count"])

    def test_identity_mismatch_is_rejected(self) -> None:
        self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=20_000,
        )
        with self.assertRaisesRegex(ValueError, "strategy_registry_identity_mismatch"):
            self.registry.ingest_night_learning(
                "other-jade",
                self.learning_snapshot(candidate_id="candidate-2"),
                now_ms=21_000,
            )

    def test_unsafe_night_snapshot_is_rejected_fail_closed(self) -> None:
        dangerous = self.learning_snapshot()
        dangerous["automatic_promotion"] = True
        with self.assertRaisesRegex(ValueError, "automatic_promotion_forbidden"):
            self.registry.ingest_night_learning("jade-test", dangerous, now_ms=20_000)

        raw = self.learning_snapshot()
        raw["raw_conversation_text_used"] = True
        with self.assertRaisesRegex(ValueError, "raw_conversation_text_forbidden"):
            self.registry.ingest_night_learning("jade-test", raw, now_ms=20_000)

    def test_strategy_candidate_requires_sandbox_and_explicit_promotion_gate(self) -> None:
        dangerous = self.learning_snapshot()
        dangerous["experiments"][0]["sandbox_required"] = False
        with self.assertRaisesRegex(ValueError, "strategy_experiment_sandbox_required"):
            self.registry.ingest_night_learning("jade-test", dangerous, now_ms=20_000)

        dangerous = self.learning_snapshot()
        dangerous["experiments"][0]["requires_explicit_promotion_approval"] = False
        with self.assertRaisesRegex(ValueError, "strategy_explicit_promotion_required"):
            self.registry.ingest_night_learning("jade-test", dangerous, now_ms=20_000)

    def test_sandbox_evaluation_requires_enough_evidence_before_validation(self) -> None:
        strategy_id = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=20_000,
        )["persisted_strategy_ids"][0]

        weak = self.registry.record_sandbox_evaluation(
            "jade-test",
            strategy_id,
            passed=True,
            score=0.31,
            confidence=MIN_PROMOTION_CONFIDENCE - 0.01,
            sample_count=MIN_PROMOTION_SAMPLES,
            now_ms=21_000,
        )
        self.assertEqual("CANDIDATE", weak["status"])

        strong = self.registry.record_sandbox_evaluation(
            "jade-test",
            strategy_id,
            passed=True,
            score=0.41,
            confidence=MIN_PROMOTION_CONFIDENCE,
            sample_count=MIN_PROMOTION_SAMPLES,
            now_ms=22_000,
        )
        self.assertEqual("VALIDATED", strong["status"])
        self.assertTrue(strong["evaluation_passed"])

    def test_promotion_requires_explicit_approval_and_never_enables_runtime(self) -> None:
        strategy_id = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=20_000,
        )["persisted_strategy_ids"][0]
        self.registry.record_sandbox_evaluation(
            "jade-test",
            strategy_id,
            passed=True,
            score=0.42,
            confidence=0.9,
            sample_count=8,
            now_ms=21_000,
        )

        with self.assertRaisesRegex(
            ValueError,
            "explicit_strategy_promotion_approval_required",
        ):
            self.registry.promote(
                "jade-test",
                strategy_id,
                explicit_approval=False,
                now_ms=22_000,
            )

        active = self.registry.promote(
            "jade-test",
            strategy_id,
            explicit_approval=True,
            now_ms=22_000,
        )
        self.assertEqual("ACTIVE", active["status"])
        self.assertTrue(active["explicit_promotion_approved"])
        self.assertFalse(active["runtime_application_enabled"])
        self.assertFalse(active["automatic_activation"])
        self.assertFalse(active["automatic_promotion"])

    def test_new_version_can_replace_then_rollback_to_previous_active_strategy(self) -> None:
        first_id = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(candidate_id="candidate-1", source_revision=41),
            now_ms=20_000,
        )["persisted_strategy_ids"][0]
        self.registry.record_sandbox_evaluation(
            "jade-test",
            first_id,
            passed=True,
            score=0.40,
            confidence=0.9,
            sample_count=8,
            now_ms=21_000,
        )
        self.registry.promote(
            "jade-test",
            first_id,
            explicit_approval=True,
            now_ms=22_000,
        )

        second_result = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(candidate_id="candidate-2", source_revision=55),
            now_ms=30_000,
        )
        second_id = second_result["persisted_strategy_ids"][0]
        second_entry = next(
            item for item in second_result["registry"]["entries"]
            if item["strategy_id"] == second_id
        )
        self.assertEqual(2, second_entry["version"])

        self.registry.record_sandbox_evaluation(
            "jade-test",
            second_id,
            passed=True,
            score=0.52,
            confidence=0.92,
            sample_count=10,
            now_ms=31_000,
        )
        second_active = self.registry.promote(
            "jade-test",
            second_id,
            explicit_approval=True,
            now_ms=32_000,
        )
        self.assertEqual(first_id, second_active["replaces_strategy_id"])

        rolled_back = self.registry.rollback(
            "jade-test",
            second_id,
            explicit_approval=True,
            now_ms=33_000,
        )
        self.assertEqual("ROLLED_BACK", rolled_back["status"])
        self.assertEqual(first_id, rolled_back["restored_strategy_id"])

        final = self.registry.snapshot("jade-test", now_ms=34_000)
        first = next(item for item in final["entries"] if item["strategy_id"] == first_id)
        second = next(item for item in final["entries"] if item["strategy_id"] == second_id)
        self.assertEqual("ACTIVE", first["status"])
        self.assertEqual("ROLLED_BACK", second["status"])
        self.assertFalse(first["runtime_application_enabled"])
        self.assertFalse(second["runtime_application_enabled"])

    def test_failed_sandbox_evaluation_is_rejected(self) -> None:
        strategy_id = self.registry.ingest_night_learning(
            "jade-test",
            self.learning_snapshot(),
            now_ms=20_000,
        )["persisted_strategy_ids"][0]
        rejected = self.registry.record_sandbox_evaluation(
            "jade-test",
            strategy_id,
            passed=False,
            score=-0.10,
            confidence=0.9,
            sample_count=8,
            notes="Le challenger régresse.",
            now_ms=21_000,
        )
        self.assertEqual("REJECTED", rejected["status"])
        with self.assertRaisesRegex(ValueError, "strategy_not_validated"):
            self.registry.promote(
                "jade-test",
                strategy_id,
                explicit_approval=True,
                now_ms=22_000,
            )


if __name__ == "__main__":
    unittest.main()
