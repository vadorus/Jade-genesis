from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path

from shared_genesis_state import SharedGenesisStateStore
from vps_night_cycle import (
    MIN_CYCLE_INTERVAL_MS,
    PIXEL_INACTIVE_AFTER_MS,
    NightCycleJournal,
    VpsNightCycleSupervisor,
)


class VpsNightCycleSupervisorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-vps-night-")
        root = Path(self.temp.name)
        self.state = SharedGenesisStateStore(root / "state.json")
        self.journal = NightCycleJournal(root / "night.json")
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
        )

    def test_active_pixel_blocks_supervised_cycle(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS + 1)
        result = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("SKIPPED", result["status"])
        self.assertEqual("pixel_active", result["reason"])
        self.assertEqual([], self.journal.load())

    def test_inactive_pixel_runs_bounded_review_and_publishes_report(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        result = self.supervisor().run_once(now_ms=self.now)

        self.assertEqual("SUCCESS", result["status"])
        self.assertEqual(24, result["runtime_observation_count"])
        self.assertEqual(1, result["evolution_validated_count"])
        self.assertFalse(result["promotion_performed"])
        self.assertFalse(result["shell_execution_performed"])

        reports = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_night_cycle_report"
        ]
        self.assertEqual(1, len(reports))
        published = json.loads(reports[0]["payload"])
        self.assertFalse(published["promotion_performed"])
        self.assertFalse(published["shell_execution_performed"])
        maintenance = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_maintenance_snapshot"
        ]
        self.assertEqual(1, len(maintenance))
        learned = json.loads(maintenance[0]["payload"])
        self.assertEqual(["await_explicit_user_approval"], learned["insights"])
        self.assertFalse(learned["promotion_performed"])

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
        )
        second = restarted.run_once(
            now_ms=int(first["completed_at"]) + 60 * 60 * 1_000
        )
        self.assertEqual("SKIPPED", second["status"])
        self.assertEqual("cadence_guard", second["reason"])

    def test_missing_eval_data_is_partial_without_invented_learning(self) -> None:
        self.seed(
            self.now - PIXEL_INACTIVE_AFTER_MS - 1,
            include_reviews=False,
        )
        result = self.supervisor().run_once(now_ms=self.now)
        self.assertEqual("PARTIAL", result["status"])
        self.assertEqual(0, result["runtime_observation_count"])
        self.assertEqual(0, result["evolution_candidate_count"])

    def test_supervisor_is_disabled_outside_vps(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        result = self.supervisor(node_kind="PC").run_once(now_ms=self.now)
        self.assertEqual("SKIPPED", result["status"])
        self.assertEqual("requires_vps", result["reason"])

    def test_replica_event_allowlist_rejects_unknown_kinds(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
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
