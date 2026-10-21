"""Regression tests for the GitHub-first coordinator. No network and no writes."""
import base64
import copy
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from coordinator import (
    CoordinationError, GitHubStore, StaleState, apply_plan, norm_path,
    overlaps, plan, validate,
)

REPO = "vadorus/Jade-genesis"
GODOT = "vadorus/addonhardwaregame2d"


def fixture():
    return {
        "schema": 1, "revision": 0,
        "agents": {
            "claude": {"available": True, "slots": 2, "quota_tasks": 3,
                       "skills": ["review", "architecture"]},
            "codex": {"available": True, "slots": 2, "quota_tasks": 2,
                      "skills": ["python", "godot"]},
            "gemini": {"available": True, "slots": 1, "quota_tasks": 1,
                       "skills": ["python", "roblox"]},
        },
        "tasks": [],
        "history": [],
    }


def task(ident, zones, *, repo=REPO, skill="python", priority=10,
         status="queued", depends_on=None, writer=None, reviewer=None):
    result = dict(id=ident, zones=zones, repo=repo, skill=skill,
                  priority=priority, status=status,
                  depends_on=depends_on or [])
    if writer:
        result.update(writer=writer, reviewer=reviewer)
    return result


class InMemoryGitHubStore(GitHubStore):
    """Simulates GitHub Contents blob-sha compare-and-swap for adversarial tests."""

    def __init__(self, initial):
        super().__init__(REPO, "integration-test", "test-token")
        self.canonical = copy.deepcopy(initial)
        self.counter = 1

    def request(self, url, *, method="GET", data=None):
        if method == "GET":
            raw = json.dumps(self.canonical, ensure_ascii=False).encode("utf-8")
            return {"content": base64.b64encode(raw).decode("ascii"),
                    "sha": str(self.counter)}
        if data["sha"] != str(self.counter):
            raise StaleState("Concurrent state update")
        self.canonical = json.loads(base64.b64decode(data["content"]))
        self.counter += 1
        return {"content": {"sha": str(self.counter)}}


class CoordinatorTests(unittest.TestCase):
    def test_paths_and_unicode_normalization(self):
        self.assertTrue(overlaps("Scripts/", "scripts/core.py"))
        self.assertTrue(overlaps("Straße/", "STRASSE/core.py"))
        self.assertTrue(overlaps("scripts", "scripts/core.py"))
        self.assertFalse(overlaps("scripts", "scripts-old/x.py"))
        self.assertFalse(overlaps("scripts/a.py", "scripts/a.py.bak"))
        self.assertEqual(norm_path("A/"), "a")

    def test_malformed_zones_fail_closed(self):
        for path in ("/root", "../root", "scripts/../core", "scripts\\file.py",
                     "scripts/*.py", "scripts/ file", "", ".", "scripts//x",
                     "scripts/(all)", "scripts/;anything", " scripts/x"):
            with self.subTest(path=path), self.assertRaises(CoordinationError):
                norm_path(path)

    def test_conflicting_tasks_assign_only_one(self):
        data = fixture()
        data["tasks"] = [task("A", ["scripts/"], priority=20),
                         task("B", ["scripts/core.py"], priority=10)]
        result = plan(data)
        self.assertEqual([x["task"] for x in result["assignments"]], ["A"])
        self.assertEqual(result["blocked"][0]["reason"], "reservation_conflict")

    def test_existing_reservation_blocks_new_writer(self):
        data = fixture()
        data["tasks"] = [
            task("old", ["src/"], status="in_progress", writer="gemini", reviewer="claude"),
            task("new", ["src/module.py"]),
        ]
        result = plan(data)
        self.assertEqual(result["assignments"], [])
        self.assertEqual(result["blocked"][0]["details"], ["old"])

    def test_separate_repositories_do_not_conflict(self):
        data = fixture()
        data["tasks"] = [task("JADE", ["scripts/"]),
                         task("GODOT", ["scripts/"], repo=GODOT)]
        self.assertEqual(len(plan(data)["assignments"]), 2)

    def test_distinct_reviewer_and_agent_capabilities(self):
        data = fixture()
        data["tasks"] = [task("game", ["scripts/gd"], skill="godot")]
        result = plan(data)
        self.assertEqual(result["assignments"][0]["writer"], "codex")
        self.assertEqual(result["assignments"][0]["reviewer"], "claude")

    def test_reviewer_unavailable_blocks(self):
        data = fixture()
        data["agents"]["claude"]["available"] = False
        data["tasks"] = [task("x", ["a"])]
        self.assertEqual(plan(data)["blocked"][0]["reason"], "reviewer_unavailable")

    def test_zero_budget_blocks_without_guessing(self):
        data = fixture()
        data["agents"]["codex"]["quota_tasks"] = 0
        data["agents"]["gemini"]["quota_tasks"] = 0
        data["tasks"] = [task("x", ["a"])]
        self.assertEqual(plan(data)["blocked"][0]["reason"], "no_worker_capacity")

    def test_dependencies_block_until_done(self):
        data = fixture()
        data["tasks"] = [task("setup", ["a"], status="done"),
                         task("next", ["b"], depends_on=["setup"])]
        self.assertEqual([x["task"] for x in plan(data)["assignments"]], ["next"])
        data["tasks"][0]["status"] = "queued"
        self.assertIn("dependencies", [x["reason"] for x in plan(data)["blocked"]])

    def test_priority_is_deterministic(self):
        data = fixture()
        data["tasks"] = [task("B", ["b"], priority=1),
                         task("A", ["a"], priority=9)]
        self.assertEqual([x["task"] for x in plan(data)["assignments"]], ["A", "B"])

    def test_two_slots_capacity_and_quotas(self):
        data = fixture()
        data["agents"]["gemini"]["available"] = False
        data["tasks"] = [task(str(i), [f"lib/{i}.py"]) for i in range(3)]
        result = plan(data)
        self.assertEqual(len(result["assignments"]), 2)
        self.assertEqual(result["blocked"][0]["reason"], "no_worker_capacity")

    def test_apply_is_pure_and_persists_history(self):
        data = fixture()
        data["tasks"] = [task("x", ["x.py"])]
        result = apply_plan(data, plan(data), timestamp="2026-10-21T11:00:00Z")
        self.assertEqual(data["tasks"][0]["status"], "queued")
        self.assertEqual(result["tasks"][0]["status"], "reserved")
        self.assertEqual(result["revision"], 1)
        self.assertEqual(result["history"][0]["event"], "reserved")
        self.assertEqual(plan(result)["assignments"], [])

    def test_stale_writers_refused(self):
        store = InMemoryGitHubStore(fixture())
        first, old_sha = store.read()
        second, also_old_sha = store.read()
        first["revision"] = 1
        second["revision"] = 2
        with patch.dict(os.environ, {"ORCH_ALLOW_GITHUB_WRITES": "1"}):
            store.save(first, old_sha)
            with self.assertRaises(StaleState):
                store.save(second, also_old_sha)
        self.assertEqual(store.read()[0]["revision"], 1)

    def test_no_token_or_optin_no_write(self):
        store = InMemoryGitHubStore(fixture())
        with patch.dict(os.environ, {"ORCH_ALLOW_GITHUB_WRITES": "0"}):
            with self.assertRaises(CoordinationError):
                store.save(fixture(), "1")
        self.assertEqual(store.counter, 1)

    def test_invalid_reserved_pair_refused(self):
        data = fixture()
        data["tasks"] = [task("x", ["x"], status="reserved",
                              writer="codex", reviewer="codex")]
        with self.assertRaises(CoordinationError):
            validate(data)

    def test_unknown_repo_and_duplicate_ids_refused(self):
        data = fixture()
        data["tasks"] = [task("x", ["a"]), task("x", ["b"])]
        with self.assertRaises(CoordinationError):
            validate(data)
        data["tasks"] = [task("x", ["a"], repo="not/approved")]
        with self.assertRaises(CoordinationError):
            validate(data)

    def test_actual_repository_initial_state_is_safe(self):
        path = Path(__file__).with_name("state.json")
        state = json.loads(path.read_text(encoding="utf-8"))
        validate(state)
        result = plan(state)
        self.assertFalse(result["assignments"])
        self.assertTrue(all(not agent["available"] for agent in state["agents"].values()))


if __name__ == "__main__":
    unittest.main()
