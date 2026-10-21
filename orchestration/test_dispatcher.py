"""Synthetic, offline tests: no GitHub tokens or real agents."""
import unittest
from pathlib import Path
import importlib.util

SPEC = importlib.util.spec_from_file_location("dispatcher", Path(__file__).with_name("dispatcher.py"))
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


def state():
    return {
        "agents": [
            {"id": "Claude", "status": "available", "quota_units": 3},
            {"id": "Codex", "status": "available", "quota_units": 4},
            {"id": "Antigravity", "status": "available", "quota_units": 3},
        ],
        "tasks": [
            {"id": "#201", "repo": "vadorus/Jade-genesis", "zones": ["orchestration/a.py"],
             "kind": "implementation", "priority": 90, "estimated_units": 2, "authorized": True}
        ],
        "claims": []
    }


class PlannerTests(unittest.TestCase):
    def test_assigns_codex_and_claude(self):
        result = mod.plan(state())
        self.assertEqual((result["assignments"][0]["writer"], result["assignments"][0]["reviewer"]), ("Codex", "Claude"))
        self.assertTrue(result["no_agents_started"])

    def test_no_approval_no_work(self):
        value = state(); value["tasks"][0]["authorized"] = False
        self.assertEqual(mod.plan(value)["blocked"][0]["reason"], "requires_human_authorization")

    def test_blocks_existing_claim(self):
        value = state(); value["claims"].append({"repo": "vadorus/Jade-genesis", "task_id": "issue:89", "zones": ["orchestration/"]})
        self.assertEqual(mod.plan(value)["blocked"][0]["reason"], "reserved_zone_conflict")

    def test_blocks_case_alias(self):
        value = state(); value["claims"].append({"repo": "vadorus/jade-genesis", "task_id": "pr:99", "zones": ["ORCHESTRATION/A.PY"]})
        self.assertEqual(mod.plan(value)["blocked"][0]["reason"], "reserved_zone_conflict")

    def test_directory_boundary(self):
        self.assertFalse(mod.overlaps(["scripts"], ["scripts-extra/a.py"]))

    def test_unicode_normalization(self):
        self.assertTrue(mod.overlaps(["docs/État.md"], ["docs/E\u0301TAT.md"]))

    def test_bad_paths_fail_closed(self):
        for bad in ("../secret", "/etc/passwd", "a\\b", "a;bad", "a/./b", "a//b", chr(96) + "scripts/" + chr(96)):
            with self.subTest(bad=bad), self.assertRaises(mod.PlanningError):
                mod.zone_key(bad)

    def test_dependency_wait(self):
        value = state(); value["tasks"][0]["dependencies"] = ["#missing"]
        self.assertEqual(mod.plan(value)["blocked"][0]["reason"], "dependencies_unmet")

    def test_quota_exhausted_fallback(self):
        value = state(); value["agents"][1]["quota_units"] = 0
        self.assertEqual(mod.plan(value)["assignments"][0]["writer"], "Antigravity")

    def test_unknown_quota_no_guess(self):
        value = state(); value["agents"][1]["quota_units"] = None; value["agents"][2]["quota_units"] = None
        self.assertEqual(mod.plan(value)["blocked"][0]["reason"], "no_writer_with_confirmed_quota")

    def test_reviewer_unavailable_blocks(self):
        value = state(); value["agents"][0]["status"] = "offline"
        self.assertEqual(mod.plan(value)["blocked"][0]["reason"], "reviewer_unavailable_or_quota_unknown")

    def test_architecture_claude_writer_codex_reviews(self):
        value = state(); value["tasks"][0]["kind"] = "architecture"
        got = mod.plan(value)["assignments"][0]
        self.assertEqual((got["writer"], got["reviewer"]), ("Claude", "Codex"))

    def test_two_tasks_one_zone_only_one_assignment(self):
        value = state()
        second = dict(value["tasks"][0], id="#202", zones=["orchestration/a.py"])
        value["tasks"].append(second)
        result = mod.plan(value)
        self.assertEqual(len(result["assignments"]), 1)
        self.assertEqual(result["blocked"][0]["reason"], "reserved_zone_conflict")

    def test_distinct_files_can_parallelize(self):
        value = state(); value["tasks"].append(dict(value["tasks"][0], id="#202", zones=["orchestration/b.py"], estimated_units=2))
        self.assertEqual(len(mod.plan(value)["assignments"]), 2)

    def test_priority(self):
        value = state(); value["tasks"].append(dict(value["tasks"][0], id="#202", priority=100, zones=["orchestration/a.py"]))
        self.assertEqual(mod.plan(value)["assignments"][0]["task_id"], "#202")

    def test_synthetic_github_claims_untrusted_ignored(self):
        def fake(url, headers):
            if "issues?" in url:
                return [{"number": 8, "user": {"login": "stranger"}, "body": "Réservation-état: Réservée\nZones: orchestration/"}]
            return []
        self.assertEqual(mod.github_claims(["vadorus/Jade-genesis"], [], fetch=fake), [])

    def test_synthetic_github_claims_trusted_conflict(self):
        def fake(url, headers):
            if "issues?" in url:
                return [{"number": 8, "user": {"login": "vadorus"}, "body": "Réservation-état: Réservée\nZones: orchestration/"}]
            return []
        claims = mod.github_claims(["vadorus/Jade-genesis"], [], fetch=fake)
        self.assertEqual(mod.plan(state(), claims)["blocked"][0]["reason"], "reserved_zone_conflict")

    def test_synthetic_github_malformed_trusted_blocks(self):
        def fake(url, headers):
            if "issues?" in url:
                return [{"number": 8, "user": {"login": "vadorus"}, "body": "Réservation-état : Réservée\nZones: orchestration/"}]
            return []
        with self.assertRaises(mod.PlanningError):
            mod.github_claims(["vadorus/Jade-genesis"], [], fetch=fake)

    def test_synthetic_github_pr_files_conflict(self):
        def fake(url, headers):
            if "issues?" in url: return []
            if "pulls?" in url: return [{"number": 12, "changed_files": 1}]
            return [{"filename": "orchestration/a.py"}]
        claims = mod.github_claims(["vadorus/Jade-genesis"], [], fetch=fake)
        self.assertEqual(mod.plan(state(), claims)["blocked"][0]["details"], ["pr:12"])

    def test_incomplete_pr_inventory_fails_closed(self):
        def fake(url, headers):
            if "issues?" in url: return []
            if "pulls?" in url: return [{"number": 12, "changed_files": 2}]
            return [{"filename": "orchestration/a.py"}]
        with self.assertRaises(mod.PlanningError):
            mod.github_claims(["vadorus/Jade-genesis"], [], fetch=fake)


if __name__ == "__main__":
    unittest.main()
