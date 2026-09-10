from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path

from night_learning_lab import NightLearningLab
from shared_genesis_state import SharedGenesisStateStore
from strategy_registry import StrategyRegistry
from vps_night_cycle import (
    MIN_CYCLE_INTERVAL_MS,
    PIXEL_INACTIVE_AFTER_MS,
    NightCycleJournal,
    VpsNightCycleSupervisor,
)


class FakeResearchProvider:
    def search(self, query: str) -> list[dict]:
        return [{
            "provider": "FakePublic",
            "title": "Bounded public evidence",
            "url": "https://example.test/night-learning",
            "snippet": f"Evidence for {query[:80]}",
            "confidence": 0.7,
        }]


class VpsNightCycleSupervisorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-vps-night-")
        root = Path(self.temp.name)
        self.state = SharedGenesisStateStore(root / "state.json")
        self.journal = NightCycleJournal(root / "night.json")
        self.strategy_registry = StrategyRegistry(root / "strategy-registry.json")
        self.learning_lab = NightLearningLab(FakeResearchProvider())
        self.now = int(time.time() * 1_000)

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

    def seed(self, phone_observed_at: int, include_reviews: bool = True) -> None:
        events = [
            self.event(
                "phone-1",
                "phone_node_snapshot",
                "pixel-test",
                {"observed_at": phone_observed_at, "battery_percent": 80},
                phone_observed_at,
            ),
            self.event(
                "memory-1",
                "memory_cursor",
                "memory-v2",
                {"id": 12, "observed_at": phone_observed_at},
                phone_observed_at,
            ),
            self.event(
                "config-1",
                "config_snapshot",
                "active",
                {"config": {"config_id": "cfg-1"}, "observed_at": phone_observed_at},
                phone_observed_at,
            ),
        ]
        if include_reviews:
            events.extend([
                self.event(
                    "runtime-1",
                    "runtime_eval_snapshot",
                    "current",
                    {
                        "observation_count": 24,
                        "score": 92.5,
                        "confidence": 0.9,
                        "groups": [],
                    },
                    phone_observed_at,
                ),
                self.event(
                    "evolution-1",
                    "evolution_snapshot",
                    "current",
                    {
                        "automatic_promotion": False,
                        "candidates": [
                            {"candidate_id": "evo-1", "status": "VALIDATED"},
                            {"candidate_id": "evo-2", "status": "TESTING"},
                        ],
                    },
                    phone_observed_at,
                ),
            ])
        self.state.sync({
            "schema_version": 1,
            "identity_id": "jade-test",
            "replica_id": "pixel-test",
            "known_revision": 0,
            "events": events,
        })

    def supervisor(self, node_kind: str = "VPS") -> VpsNightCycleSupervisor:
        return VpsNightCycleSupervisor(
            config={
                "node_id": "vps-test",
                "node_kind": node_kind,
                "node_name": "VPS test",
            },
            state_store=self.state,
            journal=self.journal,
            learning_lab=self.learning_lab,
            strategy_registry=self.strategy_registry,
        )

    def test_active_pixel_blocks_supervised_cycle(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS + 1)
        result = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("SKIPPED", result["status"])
        self.assertEqual("pixel_active", result["reason"])
        self.assertEqual([], self.journal.load())

    def test_inactive_pixel_runs_bounded_learning_and_publishes_report(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        result = self.supervisor().run_once(now_ms=self.now)

        self.assertEqual("SUCCESS", result["status"])
        self.assertEqual(24, result["runtime_observation_count"])
        self.assertEqual(1, result["evolution_validated_count"])
        self.assertGreaterEqual(result["research_question_count"], 1)
        self.assertGreaterEqual(result["hypothesis_count"], 1)
        self.assertGreaterEqual(result["experiment_count"], 1)
        self.assertGreaterEqual(result["improvement_candidate_count"], 1)
        self.assertEqual(0, result["strategy_registry_persisted_count"])
        self.assertFalse(result["automatic_experiment_execution"])
        self.assertFalse(result["promotion_performed"])
        self.assertFalse(result["strategy_runtime_application_performed"])
        self.assertFalse(result["production_code_rewrite_performed"])
        self.assertFalse(result["shell_execution_performed"])

        reports = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_night_cycle_report"
        ]
        self.assertEqual(1, len(reports))
        published = json.loads(reports[0]["payload"])
        self.assertFalse(published["promotion_performed"])
        self.assertFalse(published["strategy_runtime_application_performed"])
        self.assertFalse(published["shell_execution_performed"])

        learning = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_learning_snapshot"
        ]
        self.assertEqual(1, len(learning))
        learning_payload = json.loads(learning[0]["payload"])
        self.assertGreaterEqual(len(learning_payload["research_questions"]), 1)
        self.assertGreaterEqual(len(learning_payload["hypotheses"]), 1)
        self.assertGreaterEqual(len(learning_payload["experiments"]), 1)
        self.assertGreaterEqual(len(learning_payload["improvement_candidates"]), 1)
        self.assertFalse(learning_payload["automatic_promotion"])
        self.assertFalse(learning_payload["production_code_rewrite"])
        self.assertFalse(learning_payload["shell_execution"])

        registry_events = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_strategy_registry_snapshot"
        ]
        self.assertEqual(1, len(registry_events))
        registry_payload = json.loads(registry_events[0]["payload"])
        self.assertEqual("jade-test", registry_payload["identity_id"])
        self.assertEqual(0, registry_payload["entry_count"])
        self.assertFalse(registry_payload["automatic_promotion"])
        self.assertFalse(registry_payload["automatic_runtime_application"])
        self.assertFalse(registry_payload["runtime_application_performed"])

        maintenance = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_maintenance_snapshot"
        ]
        self.assertEqual(1, len(maintenance))
        learned = json.loads(maintenance[0]["payload"])
        self.assertIn("await_explicit_user_approval", learned["insights"])
        self.assertIn("review_night_learning_candidates", learned["insights"])
        self.assertFalse(learned["promotion_performed"])
        self.assertFalse(learned["strategy_runtime_application_performed"])

    def test_outcome_strategy_is_persisted_and_shared_without_auto_activation(self) -> None:
        phone_time = self.now - PIXEL_INACTIVE_AFTER_MS - 1
        self.seed(phone_time)
        view = self.state.supervision_view()
        runtime_at = phone_time + 1
        groups = [
            {
                "node_id": "pc-node",
                "node_name": "PC",
                "task_kind": "brain_chat",
                "model": "model-fast",
                "brain_profile": "code",
                "samples": 12,
                "success_rate": 1.0,
                "average_duration_ms": 1_000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 20.0,
                "outcome_samples": 8,
                "positive_outcomes": 7,
                "negative_outcomes": 1,
                "corrections": 1,
                "outcome_quality_score": 0.72,
                "outcome_confidence": 0.90,
            },
            {
                "node_id": "vps-node",
                "node_name": "VPS",
                "task_kind": "brain_chat",
                "model": "model-slow",
                "brain_profile": "code",
                "samples": 12,
                "success_rate": 1.0,
                "average_duration_ms": 1_100.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 18.0,
                "outcome_samples": 8,
                "positive_outcomes": 4,
                "negative_outcomes": 4,
                "corrections": 1,
                "outcome_quality_score": 0.10,
                "outcome_confidence": 0.88,
            },
        ]
        self.state.sync({
            "schema_version": 1,
            "identity_id": "jade-test",
            "replica_id": "pixel-test",
            "known_revision": view["revision"],
            "events": [self.event(
                "runtime-2",
                "runtime_eval_snapshot",
                "current",
                {
                    "observation_count": 24,
                    "score": 94.0,
                    "confidence": 0.92,
                    "outcome_feedback_count": 16,
                    "overall_outcome_quality": 0.41,
                    "groups": groups,
                },
                runtime_at,
            )],
        })

        result = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("SUCCESS", result["status"])
        self.assertGreaterEqual(result["strategy_registry_persisted_count"], 1)
        self.assertGreaterEqual(result["strategy_registry_candidate_count"], 1)
        self.assertFalse(result["promotion_performed"])
        self.assertFalse(result["strategy_runtime_application_performed"])

        local = self.strategy_registry.snapshot("jade-test", now_ms=self.now + 1)
        self.assertGreaterEqual(local["candidate_count"], 1)
        entry = next(
            item for item in local["entries"]
            if item["status"] == "CANDIDATE"
        )
        self.assertEqual("routing.profile_model_preference", entry["target"])
        self.assertEqual("code", entry["brain_profile"])
        self.assertEqual("model-fast", entry["preferred_model"])
        self.assertFalse(entry["runtime_application_enabled"])
        self.assertFalse(entry["automatic_promotion"])

        registry_events = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_strategy_registry_snapshot"
        ]
        self.assertEqual(1, len(registry_events))
        shared = json.loads(registry_events[0]["payload"])
        self.assertGreaterEqual(shared["candidate_count"], 1)
        self.assertGreaterEqual(len(shared["entries"]), 1)
        self.assertEqual("CANDIDATE", shared["entries"][0]["status"])
        self.assertFalse(shared["automatic_promotion"])
        self.assertFalse(shared["automatic_runtime_application"])
        self.assertFalse(shared["runtime_application_performed"])
        self.assertIn("review_strategy_registry_candidates", result["insights"])

    def test_cadence_guard_blocks_repeat(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        first = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("SUCCESS", first["status"])

        second = self.supervisor().run_once(
            now_ms=int(first["completed_at"]) + MIN_CYCLE_INTERVAL_MS - 1
        )
        self.assertEqual("SKIPPED", second["status"])
        self.assertEqual("cadence_guard", second["reason"])

    def test_shared_report_preserves_cadence_if_local_journal_is_lost(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        first = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("SUCCESS", first["status"])

        empty_journal = NightCycleJournal(Path(self.temp.name) / "empty.json")
        restarted = VpsNightCycleSupervisor(
            config={"node_id": "vps-test", "node_kind": "VPS"},
            state_store=self.state,
            journal=empty_journal,
            learning_lab=self.learning_lab,
            strategy_registry=self.strategy_registry,
        )
        second = restarted.run_once(
            now_ms=int(first["completed_at"]) + 60 * 60 * 1_000
        )
        self.assertEqual("SKIPPED", second["status"])
        self.assertEqual("cadence_guard", second["reason"])

    def test_missing_eval_data_is_partial_without_invented_runtime_metrics(self) -> None:
        self.seed(
            self.now - PIXEL_INACTIVE_AFTER_MS - 1,
            include_reviews=False,
        )
        result = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("PARTIAL", result["status"])
        self.assertEqual(0, result["runtime_observation_count"])
        self.assertEqual(0, result["evolution_candidate_count"])
        self.assertGreaterEqual(result["improvement_candidate_count"], 1)
        self.assertFalse(result["strategy_runtime_application_performed"])

    def test_supervisor_is_disabled_outside_vps(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        result = self.supervisor(node_kind="PC").run_once(now_ms=self.now)
        self.assertEqual("SKIPPED", result["status"])
        self.assertEqual("requires_vps", result["reason"])

    def test_replica_event_allowlist_accepts_learning_and_strategy_but_rejects_unknown_kinds(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        accepted = self.state.append_replica_event(
            identity_id="jade-test",
            replica_id="vps-test",
            kind="vps_learning_snapshot",
            entity_id="current",
            payload="{}",
        )
        self.assertGreaterEqual(accepted["server_revision"], 1)

        strategy = self.state.append_replica_event(
            identity_id="jade-test",
            replica_id="vps-test",
            kind="vps_strategy_registry_snapshot",
            entity_id="current",
            payload="{}",
        )
        self.assertGreaterEqual(strategy["server_revision"], 1)

        with self.assertRaisesRegex(ValueError, "unsupported_replica_event_kind"):
            self.state.append_replica_event(
                identity_id="jade-test",
                replica_id="vps-test",
                kind="shell_command",
                entity_id="forbidden",
                payload="{}",
            )


if __name__ == "__main__":
    unittest.main()
