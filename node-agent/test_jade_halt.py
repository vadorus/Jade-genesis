"""Regression tests for the local cooperative HALT (synthetic data only)."""
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import jade_halt
from jade_halt import HaltInterrupted, JadeHalt, run_steps


class HaltTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.halt = JadeHalt(Path(self.temp.name) / "halt.json")

    def test_missing_state_is_running(self):
        self.assertFalse(self.halt.is_halted())

    def test_persists_halted_across_instance_restart(self):
        self.halt.halt("operator halt")
        self.assertTrue(JadeHalt(self.halt.path).is_halted())

    def test_rearm_requires_explicit_named_human(self):
        self.halt.halt("hold")
        with self.assertRaises(PermissionError):
            self.halt.rearm(explicit_approval=False, operator="tester", reason="reviewed")
        with self.assertRaises(PermissionError):
            self.halt.rearm(explicit_approval=True, operator="", reason="reviewed")
        self.assertTrue(self.halt.is_halted())
        self.assertFalse(self.halt.rearm(
            explicit_approval=True, operator="tester", reason="reviewed"
        )["state"] == "HALTED")
        events = [json.loads(x) for x in self.halt.journal_path.read_text().splitlines()]
        self.assertEqual(["HALTED", "RUNNING"],
                         [x["state"] for x in events if x["kind"] == "halt_transition"])

    def test_auto_cannot_rearm(self):
        self.halt.halt("held", source="AUTO:network_guard")
        with self.assertRaises(PermissionError):
            self.halt.rearm(explicit_approval=False, operator="agent", reason="auto")
        self.assertTrue(self.halt.is_halted())

    def test_corrupted_primary_fails_closed_without_backup_rearming(self):
        self.halt.path.write_text("{not-json", encoding="utf-8")
        self.assertTrue(self.halt.is_halted())
        self.assertEqual("halt_state_unreadable", self.halt.status()["reason"])
        events = self.halt.journal_path.read_text(encoding="utf-8")
        self.assertIn("halt_state_unreadable", events)

    def test_concurrent_halt_is_idempotent(self):
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda i: self.halt.halt("reason", source="AUTO:probe"),
                          range(8)))
        events = [json.loads(line) for line in self.halt.journal_path.read_text().splitlines()]
        transitions = [e for e in events if e["kind"] == "halt_transition"]
        self.assertEqual(1, len(transitions))
        self.assertTrue(self.halt.is_halted())

    def test_protected_failures_are_bounded_and_interruption_not_counted(self):
        self.halt.record_protected_failure(failed=True, threshold=3)
        self.halt.record_protected_failure(failed=True, threshold=3)
        self.assertFalse(self.halt.is_halted())
        self.halt.record_protected_failure(failed=True, threshold=3)
        self.assertEqual("AUTO:protected_failures", self.halt.status()["source"])

    def test_repeated_unapproved_sealed_access(self):
        self.halt.record_sealed_access(final_exam=False, threshold=2)
        self.assertFalse(self.halt.is_halted())
        self.halt.record_sealed_access(final_exam=False, threshold=2)
        self.assertEqual("AUTO:sealed_access", self.halt.status()["source"])

    def test_synthetic_long_loop_stops_at_next_boundary_without_partial_result(self):
        completed = []
        def stage(index):
            def action():
                if index == 2:
                    self.halt.halt("simulate halt")
                completed.append(index)
                return index
            return action
        steps = [(str(i), stage(i)) for i in range(6)]
        with self.assertRaises(HaltInterrupted):
            run_steps(steps, halt=self.halt, loop="synthetic")
        self.assertEqual([0, 1, 2], completed)
        events = self.halt.journal_path.read_text(encoding="utf-8")
        self.assertIn("halt_interrupted", events)
        self.assertNotIn("FAILED_APPROACH", events)

    def test_all_four_autonomous_entries_block_when_halted(self):
        self.halt.halt("stop everything")
        import adaptive_vps_night_cycle
        import night_learning_lab
        import skill_synthesis_loop
        import skill_learning_cycle
        with patch.object(jade_halt, "_GLOBAL_HALT", self.halt):
            obj = object.__new__(adaptive_vps_night_cycle.AdaptiveVpsNightCycleSupervisor)
            result = obj.run_once()
            self.assertEqual("INTERRUPTED_BY_HALT", result["status"])
            with self.assertRaises(HaltInterrupted):
                night_learning_lab.NightLearningLab().review({"entities": []})
            synthesis = object.__new__(skill_synthesis_loop.SkillSynthesisLoop)
            with self.assertRaises(HaltInterrupted):
                synthesis.learn("identity", "goal", lambda _: {}, teacher_id="fake")
            with self.assertRaises(HaltInterrupted):
                skill_learning_cycle.run_first_learning_cycle({}, "identity")
        text = self.halt.journal_path.read_text(encoding="utf-8")
        for name in ("adaptive_vps_night_cycle", "night_learning_lab",
                     "skill_synthesis_loop", "skill_learning_cycle"):
            self.assertIn(name, text)
        self.assertIn("halt_blocked", text)

    def test_second_web_request_never_starts_after_halt(self):
        import night_learning_lab
        calls = []
        class Provider:
            def search(_self, _query):
                calls.append("called")
                self.halt.halt("stop after first request")
                return []
        lab = night_learning_lab.NightLearningLab(research_provider=Provider())
        with patch.object(jade_halt, "_GLOBAL_HALT", self.halt):
            # Inject two synthetic questions without external network.
            with patch.object(lab, "_research_questions", return_value=[
                {"question_id": "q1", "query": "offline-a"},
                {"question_id": "q2", "query": "offline-b"},
            ]):
                with self.assertRaises(HaltInterrupted):
                    lab.review({"entities": []})
        self.assertEqual(1, len(calls))

if __name__ == "__main__":
    unittest.main()
