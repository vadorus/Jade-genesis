from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from adaptive_strategy_registry import AdaptiveStrategyRegistry
from adaptive_vps_night_cycle import AdaptiveVpsNightCycleSupervisor
from shared_genesis_state import SharedGenesisStateStore
from vps_night_cycle import NightCycleJournal, PIXEL_INACTIVE_AFTER_MS


class FakeLearningLab:
    def review(self, view: dict, now_ms: int | None = None) -> dict:
        return {
            "schema_version": 1,
            "generated_at": now_ms or 0,
            "source_revision": 5,
            "signals": [{
                "kind": "outcome_quality_advantage",
                "task_kind": "brain_chat",
                "brain_profile": "balanced",
                "preferred_model": "model-a",
                "preferred_node_id": "vps-a",
                "comparison_model": "model-b",
                "comparison_node_id": "vps-b",
            }],
            "research_questions": [],
            "research_evidence": [],
            "research_errors": [],
            "hypotheses": [{
                "hypothesis_id": "hyp-1",
                "signal_kind": "outcome_quality_advantage",
            }],
            "experiments": [{
                "experiment_id": "exp-fixed",
                "hypothesis_id": "hyp-1",
            }],
            "improvement_candidates": [{
                "candidate_id": "nlc-fixed",
                "kind": "STRATEGY_HINT",
                "status": "CANDIDATE",
                "title": "Challenger de stratégie fondé sur les outcomes",
                "rationale": "Tester une préférence de profil en sandbox avant toute activation.",
                "experiment_id": "exp-fixed",
                "target": "routing.profile_model_preference",
                "sandbox_required": True,
                "automatic_activation": False,
                "automatic_promotion": False,
                "production_code_change": False,
            }],
            "external_research_attempted": False,
            "external_research_evidence_count": 0,
            "automatic_experiment_execution": False,
            "automatic_promotion": False,
            "production_code_rewrite": False,
            "shell_execution": False,
            "raw_conversation_text_used": False,
            "user_feedback_promoted_to_external_fact": False,
        }


class UnsafeLearningLab(FakeLearningLab):
    def __init__(self, unsafe_field: str):
        self.unsafe_field = unsafe_field

    def review(self, view: dict, now_ms: int | None = None) -> dict:
        result = super().review(view, now_ms)
        result[self.unsafe_field] = True
        return result


class AdaptiveVpsNightCycleSupervisorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-adaptive-night-")
        root = Path(self.temp.name)
        self.state = SharedGenesisStateStore(root / "state.json")
        self.journal = NightCycleJournal(root / "night.json")
        self.registry = AdaptiveStrategyRegistry(root / "strategy.json")
        self.now = 10_000_000
        self.seed()

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def event(event_id: str, kind: str, entity_id: str, payload: dict, created_at: int) -> dict:
        return {
            "event_id": event_id,
            "origin_node": "pixel-test",
            "kind": kind,
            "entity_id": entity_id,
            "payload": json.dumps(payload, separators=(",", ":")),
            "created_at": created_at,
        }

    def seed(self) -> None:
        observed = self.now - PIXEL_INACTIVE_AFTER_MS - 1
        events = [
            self.event(
                "phone-1",
                "phone_node_snapshot",
                "pixel-test",
                {"observed_at": observed},
                observed,
            ),
            self.event(
                "memory-1",
                "memory_cursor",
                "memory-v2",
                {"id": 1},
                observed,
            ),
            self.event(
                "config-1",
                "config_snapshot",
                "active",
                {"config": {"config_id": "cfg-1"}},
                observed,
            ),
            self.event(
                "runtime-1",
                "runtime_eval_snapshot",
                "current",
                {
                    "observation_count": 20,
                    "score": 95.0,
                    "confidence": 0.9,
                    "groups": [],
                },
                observed,
            ),
            self.event(
                "evolution-1",
                "evolution_snapshot",
                "current",
                {"automatic_promotion": False, "candidates": []},
                observed,
            ),
        ]
        self.state.sync({
            "schema_version": 1,
            "identity_id": "jade-test",
            "replica_id": "pixel-test",
            "known_revision": 0,
            "events": events,
        })

    def supervisor(self, learning_lab=None) -> AdaptiveVpsNightCycleSupervisor:
        return AdaptiveVpsNightCycleSupervisor(
            config={
                "node_id": "vps-test",
                "node_kind": "VPS",
                "node_name": "VPS test",
            },
            state_store=self.state,
            journal=self.journal,
            learning_lab=learning_lab or FakeLearningLab(),
            strategy_registry=self.registry,
        )

    def test_cycle_persists_strategy_but_does_not_activate_or_promote_it(self) -> None:
        result = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("SUCCESS", result["status"])
        self.assertEqual("OK", result["strategy_registry_status"])
        self.assertEqual(1, result["strategy_candidates_accepted"])
        self.assertEqual(1, result["strategy_candidates_created"])
        self.assertEqual(0, result["strategy_candidates_reinforced"])
        self.assertFalse(result["strategy_activation_performed"])
        self.assertFalse(result["strategy_promotion_performed"])

        snapshot = self.registry.snapshot("jade-test")
        self.assertEqual(1, snapshot["strategy_count"])
        self.assertEqual(1, snapshot["candidate_strategy_count"])
        self.assertEqual(0, snapshot["active_strategy_count"])
        self.assertFalse(snapshot["automatic_activation"])
        self.assertFalse(snapshot["automatic_promotion"])
        entry = snapshot["entries"][0]
        self.assertEqual("brain_chat", entry["scope"]["task_kind"])
        self.assertEqual("balanced", entry["scope"]["brain_profile"])
        self.assertEqual("model-a", entry["scope"]["model"])
        self.assertEqual("vps-a", entry["scope"]["node_id"])

        shared = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_maintenance_snapshot"
            and item.get("entity_id") == "adaptive-strategy-registry"
        ]
        self.assertEqual(1, len(shared))
        payload = json.loads(shared[0]["payload"])
        self.assertEqual("adaptive_strategy_registry", payload["snapshot_kind"])
        self.assertEqual(1, payload["strategy_count"])
        self.assertEqual(0, payload["active_strategy_count"])
        self.assertEqual("balanced", payload["entries"][0]["scope"]["brain_profile"])
        self.assertFalse(payload["raw_conversation_text_stored"])
        self.assertFalse(payload["user_feedback_promoted_to_external_fact"])
        self.assertFalse(payload["automatic_activation"])
        self.assertFalse(payload["automatic_promotion"])
        self.assertFalse(payload["model_weight_mutation"])
        self.assertFalse(payload["shell_execution"])

    def test_replayed_same_candidate_does_not_duplicate_or_increase_evidence(self) -> None:
        supervisor = self.supervisor()
        first = supervisor.run_once(now_ms=self.now)
        self.assertEqual("SUCCESS", first["status"])
        second = supervisor.run_once(force=True, now_ms=self.now + 1000)
        self.assertIn(second["status"], {"SUCCESS", "PARTIAL"})

        snapshot = self.registry.snapshot("jade-test")
        self.assertEqual(1, snapshot["strategy_count"])
        self.assertEqual(1, snapshot["entries"][0]["evidence_count"])

    def test_raw_conversation_learning_snapshot_is_rejected_fail_closed(self) -> None:
        result = self.supervisor(
            UnsafeLearningLab("raw_conversation_text_used")
        ).run_once(now_ms=self.now)

        self.assertEqual("SUCCESS", result["status"])
        self.assertEqual("ERROR", result["strategy_registry_status"])
        self.assertEqual("ValueError", result["strategy_registry_error"])
        self.assertFalse(result["strategy_activation_performed"])
        self.assertFalse(result["strategy_promotion_performed"])
        self.assertEqual(0, self.registry.snapshot("jade-test")["strategy_count"])
        shared = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_maintenance_snapshot"
            and item.get("entity_id") == "adaptive-strategy-registry"
        ]
        self.assertEqual([], shared)

    def test_feedback_promoted_to_external_fact_is_rejected_fail_closed(self) -> None:
        result = self.supervisor(
            UnsafeLearningLab("user_feedback_promoted_to_external_fact")
        ).run_once(now_ms=self.now)

        self.assertEqual("SUCCESS", result["status"])
        self.assertEqual("ERROR", result["strategy_registry_status"])
        self.assertEqual("ValueError", result["strategy_registry_error"])
        self.assertFalse(result["strategy_activation_performed"])
        self.assertFalse(result["strategy_promotion_performed"])
        self.assertEqual(0, self.registry.snapshot("jade-test")["strategy_count"])

    def test_status_exposes_registry_without_claiming_automatic_promotion(self) -> None:
        status = self.supervisor().status()
        self.assertEqual(
            "bounded_learning_lab+adaptive_strategy_registry",
            status["mode"],
        )
        self.assertTrue(status["strategy_persistence_automatic"])
        self.assertFalse(status["strategy_activation_automatic"])
        self.assertFalse(status["strategy_promotion_automatic"])
        self.assertFalse(status["strategy_model_weight_mutation"])
        self.assertEqual(0, status["strategy_count"])


if __name__ == "__main__":
    unittest.main()
