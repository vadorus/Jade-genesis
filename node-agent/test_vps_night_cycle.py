from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path

from night_learning_lab import NightLearningLab
from shared_genesis_state import SharedGenesisStateStore
from datetime import datetime, timezone

from vps_night_cycle import (
    MAX_DECISIONS,
    MIN_CYCLE_INTERVAL_MS,
    PIXEL_INACTIVE_AFTER_MS,
    NightCycleDecisionLog,
    NightCycleJournal,
    VpsNightCycleSupervisor,
    night_window_start_ms,
    paris_offset_ms,
    parse_night_window,
)


def utc_ms(*parts: int) -> int:
    return int(datetime(*parts, tzinfo=timezone.utc).timestamp() * 1_000)


HOUR_MS = 60 * 60 * 1_000


def future_paris_0200(days_ahead: int = 2) -> int:
    """02:00 Paris, a few days after the real clock.

    The supervisor stamps completed_at with the real clock, so scenario
    timestamps must stay in the future for the "same night" checks.
    """
    day = datetime.fromtimestamp(time.time() + days_ahead * 86_400, tz=timezone.utc).date()
    utc_midnight = int(datetime(day.year, day.month, day.day, tzinfo=timezone.utc).timestamp() * 1_000)
    guess = utc_midnight + 2 * HOUR_MS
    return guess - paris_offset_ms(guess - 2 * HOUR_MS)


NIGHT_0200_PARIS = future_paris_0200()


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

    def seed(
        self,
        phone_observed_at: int,
        include_reviews: bool = True,
        phone_extra: dict | None = None,
    ) -> None:
        events = [
            self.event(
                "phone-1",
                "phone_node_snapshot",
                "pixel-test",
                {"observed_at": phone_observed_at, "battery_percent": 80, **(phone_extra or {})},
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

    def supervisor(self, node_kind: str = "VPS", window: str | None = None) -> VpsNightCycleSupervisor:
        config = {
            "node_id": "vps-test",
            "node_kind": node_kind,
            "node_name": "VPS test",
        }
        if window is not None:
            config["night_cycle_window_paris"] = window
        return VpsNightCycleSupervisor(
            config=config,
            state_store=self.state,
            journal=self.journal,
            learning_lab=self.learning_lab,
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
        self.assertFalse(result["automatic_experiment_execution"])
        self.assertFalse(result["promotion_performed"])
        self.assertFalse(result["production_code_rewrite_performed"])
        self.assertFalse(result["shell_execution_performed"])

        reports = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_night_cycle_report"
        ]
        self.assertEqual(1, len(reports))
        published = json.loads(reports[0]["payload"])
        self.assertFalse(published["promotion_performed"])
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

        maintenance = [
            item for item in self.state.supervision_view()["entities"]
            if item.get("kind") == "vps_maintenance_snapshot"
        ]
        self.assertEqual(1, len(maintenance))
        learned = json.loads(maintenance[0]["payload"])
        self.assertIn("await_explicit_user_approval", learned["insights"])
        self.assertIn("review_night_learning_candidates", learned["insights"])
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
            learning_lab=self.learning_lab,
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

    def test_supervisor_is_disabled_outside_vps(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        result = self.supervisor(node_kind="PC").run_once(now_ms=self.now)
        self.assertEqual("SKIPPED", result["status"])
        self.assertEqual("requires_vps", result["reason"])

    def test_replica_event_allowlist_accepts_learning_but_rejects_unknown_kinds(self) -> None:
        self.seed(self.now - PIXEL_INACTIVE_AFTER_MS - 1)
        accepted = self.state.append_replica_event(
            identity_id="jade-test",
            replica_id="vps-test",
            kind="vps_learning_snapshot",
            entity_id="current",
            payload="{}",
        )
        self.assertGreaterEqual(accepted["server_revision"], 1)

        with self.assertRaisesRegex(ValueError, "unsupported_replica_event_kind"):
            self.state.append_replica_event(
                identity_id="jade-test",
                replica_id="vps-test",
                kind="shell_command",
                entity_id="forbidden",
                payload="{}",
            )


    # --- #78: presence is not use, one cycle per night window, durable trace ----

    def charging_phone(self, now: int, last_use: int) -> None:
        # The background worker publishes a fresh snapshot (here 5 min ago),
        # while the last real use of the app is older.
        self.seed(now - 5 * 60 * 1_000, phone_extra={
            "user_activity_tracked": True,
            "last_user_activity_at": last_use,
        })

    def test_paris_offset_follows_the_eu_summer_time_rule(self) -> None:
        self.assertEqual(2 * HOUR_MS, paris_offset_ms(utc_ms(2026, 7, 1, 12, 0)))
        self.assertEqual(1 * HOUR_MS, paris_offset_ms(utc_ms(2026, 12, 1, 12, 0)))
        # 2026: summer time ends on 25/10 at 01:00 UTC, starts on 29/03 at 01:00 UTC.
        self.assertEqual(2 * HOUR_MS, paris_offset_ms(utc_ms(2026, 10, 25, 0, 59)))
        self.assertEqual(1 * HOUR_MS, paris_offset_ms(utc_ms(2026, 10, 25, 1, 0)))
        self.assertEqual(1 * HOUR_MS, paris_offset_ms(utc_ms(2026, 3, 29, 0, 59)))
        self.assertEqual(2 * HOUR_MS, paris_offset_ms(utc_ms(2026, 3, 29, 1, 0)))

    def test_night_window_parsing_and_bounds(self) -> None:
        self.assertEqual((30, 390), parse_night_window("00:30-06:30"))
        for bad in (None, "", "0:30-6:30", "24:00-06:00", "06:30-06:30", 630, "00:30–06:30"):
            self.assertIsNone(parse_night_window(bad), bad)
        window = (30, 390)
        night = utc_ms(2026, 10, 17, 0, 0)                      # 02:00 Paris (UTC+2)
        self.assertEqual(utc_ms(2026, 10, 16, 22, 30),           # 00:30 Paris
                         night_window_start_ms(night, window))
        self.assertIsNone(night_window_start_ms(utc_ms(2026, 10, 17, 10, 0), window))   # 12:00 Paris
        self.assertIsNone(night_window_start_ms(utc_ms(2026, 10, 17, 4, 30), window))   # 06:30 Paris
        wrap = (23 * 60, 5 * 60)
        self.assertEqual(utc_ms(2026, 10, 16, 21, 0),            # 23:00 Paris the day before
                         night_window_start_ms(night - HOUR_MS, wrap))

    def test_charging_phone_publishing_all_night_no_longer_blocks_the_cycle(self) -> None:
        self.charging_phone(NIGHT_0200_PARIS, last_use=NIGHT_0200_PARIS - 4 * HOUR_MS)
        result = self.supervisor(window="00:30-06:30").run_once(now_ms=NIGHT_0200_PARIS)
        self.assertEqual("SUCCESS", result["status"])

    def test_recent_real_use_still_blocks_the_cycle(self) -> None:
        self.charging_phone(NIGHT_0200_PARIS, last_use=NIGHT_0200_PARIS - 30 * 60 * 1_000)
        result = self.supervisor(window="00:30-06:30").run_once(now_ms=NIGHT_0200_PARIS)
        self.assertEqual(("SKIPPED", "pixel_in_use"), (result["status"], result["reason"]))

    def test_app_without_activity_tracking_keeps_the_presence_rule(self) -> None:
        self.seed(NIGHT_0200_PARIS - 10 * 60 * 1_000)
        result = self.supervisor(window="00:30-06:30").run_once(now_ms=NIGHT_0200_PARIS)
        self.assertEqual(("SKIPPED", "pixel_active"), (result["status"], result["reason"]))

    def test_daytime_cycle_no_longer_blocks_the_next_night(self) -> None:
        # Observed on 16/10: a cycle completed at 10:56 blocked everything
        # until 06:56 the next morning with the 20 h rule.
        day = NIGHT_0200_PARIS - 15 * HOUR_MS - 4 * 60 * 1_000      # 10:56 Paris the day before
        self.charging_phone(day, last_use=day - 4 * HOUR_MS)
        first = self.supervisor(window="00:30-06:30").run_once(force=True, now_ms=day)
        self.assertEqual("SUCCESS", first["status"])
        self.assertLess(NIGHT_0200_PARIS - day, MIN_CYCLE_INTERVAL_MS)
        result = self.supervisor(window="00:30-06:30").run_once(now_ms=NIGHT_0200_PARIS)
        self.assertEqual("SUCCESS", result["status"])

    def test_one_cycle_per_night_and_none_outside_the_window(self) -> None:
        self.charging_phone(NIGHT_0200_PARIS, last_use=NIGHT_0200_PARIS - 4 * HOUR_MS)
        supervisor = self.supervisor(window="00:30-06:30")
        self.assertEqual("SUCCESS", supervisor.run_once(now_ms=NIGHT_0200_PARIS)["status"])
        again = supervisor.run_once(now_ms=NIGHT_0200_PARIS + 2 * HOUR_MS)        # 04:00
        self.assertEqual(("SKIPPED", "already_ran_tonight"), (again["status"], again["reason"]))
        afternoon = supervisor.run_once(now_ms=NIGHT_0200_PARIS + 13 * HOUR_MS)   # 15:00
        self.assertEqual(("SKIPPED", "outside_night_window"),
                         (afternoon["status"], afternoon["reason"]))
        next_night = supervisor.run_once(now_ms=NIGHT_0200_PARIS + 24 * HOUR_MS)
        self.assertEqual("SUCCESS", next_night["status"])

    def test_every_decision_is_kept_durably_with_codes_only(self) -> None:
        self.charging_phone(NIGHT_0200_PARIS, last_use=NIGHT_0200_PARIS - 30 * 60 * 1_000)
        supervisor = self.supervisor(window="00:30-06:30")
        supervisor.run_once(now_ms=NIGHT_0200_PARIS - 3 * HOUR_MS)                 # 23:00
        supervisor.run_once(now_ms=NIGHT_0200_PARIS)
        reloaded = NightCycleDecisionLog(Path(self.temp.name) / "night-cycle-decisions.json")
        self.assertEqual(
            [("SKIPPED", "outside_night_window"), ("SKIPPED", "pixel_in_use")],
            [(row["status"], row["reason"]) for row in reloaded.load()],
        )
        self.assertEqual(set(), {key for row in reloaded.load() for key in row} - {"at", "status", "reason"})
        self.assertEqual(2, len(supervisor.status()["recent_decisions"]))

    def test_decision_log_is_bounded(self) -> None:
        log = NightCycleDecisionLog(Path(self.temp.name) / "bounded.json")
        for index in range(MAX_DECISIONS + 10):
            log.record(index, "SKIPPED", "pixel_in_use")
        rows = log.load()
        self.assertEqual(MAX_DECISIONS, len(rows))
        self.assertEqual(10, rows[0]["at"])

    def test_invalid_window_is_reported_and_keeps_the_old_cadence(self) -> None:
        supervisor = self.supervisor(window="nuit")
        self.assertTrue(supervisor.status()["night_window_invalid"])
        self.assertEqual("", supervisor.status()["night_window_paris"])
        self.charging_phone(NIGHT_0200_PARIS, last_use=NIGHT_0200_PARIS - 4 * HOUR_MS)
        first = supervisor.run_once(now_ms=NIGHT_0200_PARIS)
        self.assertEqual("SUCCESS", first["status"])
        second = supervisor.run_once(now_ms=NIGHT_0200_PARIS + HOUR_MS)
        self.assertEqual(("SKIPPED", "cadence_guard"), (second["status"], second["reason"]))


if __name__ == "__main__":
    unittest.main()
