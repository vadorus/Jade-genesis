"""Offline GitHub Git Data API tests for the Phase B registry.

No real GitHub tokens, writes, API calls, or agent execution.
"""
from __future__ import annotations

import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import threading
import unittest

import dispatcher
import registry


class FakeGit:
    """Immutable commits and trees; compare-and-fast-forward is atomic under a lock."""

    def __init__(self):
        self.lock = threading.Lock()
        self.now = datetime(2026, 10, 21, 21, 0, tzinfo=timezone.utc)
        self.head = "a" * 40
        self.seq = 0
        self.patches = 0
        self.writes = []
        self.entries = {}
        self.blobs = {}
        self.trees = {"t0": {}}
        self.commits = {self.head: {"tree": "t0", "parents": []}}
        self.issues = {}
        self.comments = {}
        self.list_issues = True
        self.patch_barrier = None
        self.advance_once = False
        self.extra_tree_nodes = []
        self._initial_agent("Codex", status="available", quota=5)

    def _sha(self, prefix):
        self.seq += 1
        return prefix + format(self.seq, "039x")

    def _blob(self, item):
        content = json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n"
        digest = hashlib.sha1(content.encode("utf-8")).hexdigest()
        self.blobs[digest] = content
        return digest

    def _initial_agent(self, agent, status, quota, seconds_ago=0):
        value = {
            "schema": 1, "agent": agent, "status": status,
            "quota_declared": quota,
            "heartbeat_at": registry.timestamp(self.now - timedelta(seconds=seconds_ago)),
            "current_task": None
        }
        self.trees["t0"][registry.agent_path(agent)] = self._blob(value)

    def issue(self, number, zones="orchestration/", repo=registry.STATE_REPO):
        body = ("Responsable-IA: Codex\nRelecteur-IA: Claude\n"
                "Réservation-état: Réservée\nZones: " + zones)
        issue = {"number": number, "state": "open", "body": body,
                 "user": {"login": "vadorus"}}
        self.issues[(repo, number)] = issue
        code = dispatcher.approval_fingerprint(body, [zones])[:12]
        self.comments[(repo, number)] = [{
            "body": "/autoriser " + code,
            "user": {"login": "vadorus"},
            "author_association": "OWNER",
            "performed_via_github_app": None
        }]
        return issue

    def current(self):
        tree = self.trees[self.commits[self.head]["tree"]]
        return {p: json.loads(self.blobs[sha]) for p, sha in tree.items()}

    def advance(self):
        """Simulate another writer committing a sibling of the pending commit."""
        with self.lock:
            tree = self.commits[self.head]["tree"]
            previous = self.head
            new = self._sha("b")
            self.commits[new] = {"tree": tree, "parents": [previous]}
            self.head = new

    def request(self, method, path, payload):
        base = "repos/" + registry.STATE_REPO + "/"
        if method == "GET" and path == base + "git/ref/heads/orchestration/state":
            return {"object": {"sha": self.head}}
        if method == "GET" and path.startswith(base + "git/commits/"):
            commit = self.commits[path.split("/")[-1]]
            return {"tree": {"sha": commit["tree"]["sha"]
                             if isinstance(commit["tree"], dict) else commit["tree"]}}
        if method == "GET" and path.startswith(base + "git/trees/"):
            sha = path.split("/")[-1].split("?")[0]
            files = self.trees[sha]
            directories = set()
            for file_path in files:
                parts = file_path.split("/")
                directories.update("/".join(parts[:i]) for i in range(1, len(parts)))
            # Match GitHub's recursive trees: intermediate directories are also
            # returned as type=tree entries, including the top-level registry.
            result = [{"path": p, "type": "tree", "mode": "040000",
                       "sha": "d" * 40} for p in sorted(directories)]
            result += [{"path": p, "type": "blob", "mode": "100644", "sha": blob_sha}
                       for p, blob_sha in files.items()]
            result += self.extra_tree_nodes
            return {"truncated": False, "tree": result}
        if method == "GET" and path.startswith(base + "git/blobs/"):
            content = self.blobs[path.rsplit("/", 1)[-1]]
            return {"encoding": "base64", "content": base64.b64encode(content.encode()).decode()}
        if method == "POST" and path == base + "git/trees":
            self.writes.append((method, path))
            new = dict(self.trees[payload["base_tree"]])
            for item in payload["tree"]:
                new[item["path"]] = self._blob(json.loads(item["content"]))
            sha = self._sha("t")
            self.trees[sha] = new
            return {"sha": sha}
        if method == "POST" and path == base + "git/commits":
            self.writes.append((method, path))
            sha = self._sha("c")
            self.commits[sha] = {"tree": payload["tree"], "parents": payload["parents"]}
            return {"sha": sha}
        if method == "PATCH" and path == base + "git/refs/heads/orchestration/state":
            self.writes.append((method, path))
            if self.patch_barrier is not None:
                self.patch_barrier.wait(timeout=5)
            if payload.get("force") is not False:
                raise AssertionError("Force update forbidden")
            if self.advance_once:
                self.advance_once = False
                self.advance()
            with self.lock:
                proposed = self.commits[payload["sha"]]
                if proposed["parents"] != [self.head]:
                    raise registry.ConcurrentWrite("non-fast-forward")
                self.head = payload["sha"]
                self.patches += 1
            return {"object": {"sha": self.head}}
        if method == "GET" and path.startswith("repos/") and "/issues/" in path:
            repo, suffix = path[6:].split("/issues/", 1)
            if suffix.endswith("/comments?per_page=100&page=1"):
                number = int(suffix.split("/", 1)[0])
                return self.comments.get((repo, number), [])
            if suffix.isdigit():
                return self.issues[(repo, int(suffix))]
        if method == "GET" and path.startswith("repos/") and "/issues?" in path:
            repo = path[6:].split("/issues?", 1)[0]
            return [value for (r, _), value in self.issues.items() if r == repo] if self.list_issues else []
        if method == "GET" and "/pulls?state=open" in path:
            return []
        raise AssertionError("Unrecognized fake API call: " + method + " " + path)


class RegistryTests(unittest.TestCase):
    def setUp(self):
        self.fake = FakeGit()
        self.fake.issue(94)
        self.registry = registry.Registry(
            registry.GitHub(token="synthetic", request=self.fake.request),
            apply=True, clock=lambda: self.fake.now
        )

    def test_status_declared_agent(self):
        result = self.registry.status()
        self.assertEqual(result["agents"][0]["effective_status"], "available")
        self.assertEqual(result["taches"], [])

    def test_simulation_sends_no_writes(self):
        self.registry.apply = False
        result = self.registry.claim(registry.STATE_REPO, 94, "Codex")
        self.assertEqual(result["status"], "proposal_only")
        self.assertTrue(result["no_github_writes"])
        self.assertEqual(self.fake.writes, [])

    def test_only_one_claim_for_same_task_at_same_time(self):
        self.fake.patch_barrier = threading.Barrier(2)
        self.registry.retries = 1
        def attempt():
            try:
                return self.registry.claim(registry.STATE_REPO, 94, "Codex")["status"]
            except registry.ConcurrentWrite:
                return "conflict"
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: attempt(), range(2)))
        self.assertCountEqual(results, ["applied", "conflict"])
        self.assertEqual(self.fake.patches, 1)
        self.assertEqual(len([k for k in self.fake.current() if k.startswith("registry/tasks/")]), 1)

    def test_overlapping_distinct_tasks_concurrent_only_one_wins(self):
        self.fake.issue(95, zones="orchestration/registry.py")
        # Ignore the OTHER issue in the mock issue list to isolate the single
        # register's atomic exclusion; actual GitHub issue overlap is stricter.
        self.fake.list_issues = False
        self.fake.patch_barrier = threading.Barrier(2)
        self.registry.retries = 1
        def attempt(num):
            try:
                return self.registry.claim(registry.STATE_REPO, num, "Codex")["status"]
            except registry.ConcurrentWrite:
                return "conflict"
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(attempt, (94, 95)))
        self.assertCountEqual(results, ["applied", "conflict"])
        tasks = [v for k, v in self.fake.current().items() if k.startswith("registry/tasks/")]
        self.assertEqual(len(tasks), 1)

    def test_stale_branch_retries_and_does_not_overwrite(self):
        self.fake.advance_once = True
        result = self.registry.claim(registry.STATE_REPO, 94, "Codex")
        self.assertEqual(result["status"], "applied")
        self.assertEqual(self.fake.patches, 1)
        self.assertGreaterEqual(len([w for w in self.fake.writes if w[0] == "POST"]), 4)

    def test_expire_recover_and_resume_preserves_branch_and_head(self):
        self.registry.claim(registry.STATE_REPO, 94, "Codex")
        path = registry.task_path(registry.STATE_REPO, 94)
        # The test modifies only its simulated immutable tree, never real GitHub.
        old = self.fake.current()[path]
        old.update({"branch": "codex/94-registry", "head_sha": "1" * 40})
        with self.fake.lock:
            tree_sha = self._tree_from_current({path: old})
            root = self.fake._sha("b")
            self.fake.commits[root] = {"tree": tree_sha, "parents": [self.fake.head]}
            self.fake.head = root
        self.fake.now += timedelta(hours=3)
        self.registry.recover()
        interrupted = self.fake.current()[path]
        self.assertEqual(interrupted["state"], "interrupted")
        self.assertEqual(interrupted["head_sha"], "1" * 40)
        self.assertEqual(interrupted["branch"], "codex/94-registry")
        self.fake.list_issues = False
        self.registry.claim(registry.STATE_REPO, 94, "Codex")
        resumed = self.fake.current()[path]
        self.assertEqual(resumed["attempt"], 2)
        self.assertEqual(resumed["head_sha"], "1" * 40)
        self.assertEqual(resumed["branch"], "codex/94-registry")

    def _tree_from_current(self, overrides):
        current = dict(self.fake.trees[self.fake.commits[self.fake.head]["tree"]])
        for path, item in overrides.items():
            current[path] = self.fake._blob(item)
        sha = self.fake._sha("t")
        self.fake.trees[sha] = current
        return sha

    def test_review_waiting_three_hours_is_not_recovered(self):
        self.registry.claim(registry.STATE_REPO, 94, "Codex",
                            branch_name="codex/orchestration-registry")
        self.registry.task_change("handoff", registry.STATE_REPO, 94, "Codex",
                                  head_sha="1" * 40)
        path = registry.task_path(registry.STATE_REPO, 94)
        previous = self.fake.current()[path]
        self.assertEqual(previous["state"], "review")
        self.assertIsNone(previous["lease_until"])
        self.fake.now += timedelta(hours=3)
        with self.assertRaisesRegex(registry.RegistryError, "Transition vide"):
            self.registry.recover()
        task = self.fake.current()[path]
        self.assertEqual(task["state"], "review")
        self.assertEqual(task["head_sha"], "1" * 40)
        self.assertEqual(task["branch"], "codex/orchestration-registry")
        self.assertEqual(self.fake.patches, 2)
        with self.assertRaisesRegex(registry.RegistryError, "Tâche déjà réservée"):
            self.registry.claim(registry.STATE_REPO, 94, "Codex")

    def test_orphan_readme_seed_is_ignored_but_other_files_are_refused(self):
        init = self.fake.trees["t0"]
        init["registry/README.md"] = self.fake._blob({"seed": "root"})
        self.assertEqual(len(self.registry.status()["agents"]), 1)
        init["registry/undeclared.md"] = self.fake._blob({"other": "unexpected"})
        with self.assertRaisesRegex(registry.RegistryError, "Chemin de registre"):
            self.registry.status()

    def test_recursive_tree_contains_directory_nodes_like_real_github(self):
        tree = self.fake.request("GET", "repos/" + registry.STATE_REPO +
                                 "/git/trees/t0?recursive=1", None)
        self.assertTrue(any(x["path"] == "registry" and x["type"] == "tree"
                            for x in tree["tree"]))
        self.assertTrue(any(x["path"] == "registry/agents" and x["type"] == "tree"
                            for x in tree["tree"]))
        self.assertEqual(len(self.registry.status()["agents"]), 1)

    def test_recursive_tree_rejects_outside_or_unknown_directories(self):
        for name in ("private", "registry/secrets", "registry/tasks/nested"):
            with self.subTest(path=name):
                self.fake.extra_tree_nodes = [
                    {"path": name, "type": "tree", "mode": "040000",
                     "sha": "d" * 40}
                ]
                with self.assertRaises(registry.RegistryError):
                    self.registry.status()
        self.fake.extra_tree_nodes = []

    def test_stale_heartbeat_is_offline_and_blocks_claim(self):
        current = self.fake.current()
        a = current[registry.agent_path("Codex")]
        a["heartbeat_at"] = registry.timestamp(self.fake.now - timedelta(hours=7))
        with self.fake.lock:
            tree = self._tree_from_current({registry.agent_path("Codex"): a})
            root = self.fake._sha("b")
            self.fake.commits[root] = {"tree": tree, "parents": [self.fake.head]}
            self.fake.head = root
        self.assertEqual(self.registry.status()["agents"][0]["effective_status"], "offline")
        with self.assertRaisesRegex(registry.RegistryError, "indisponible"):
            self.registry.claim(registry.STATE_REPO, 94, "Codex")

    def test_approval_stale_after_issue_change_blocks(self):
        self.fake.issues[(registry.STATE_REPO, 94)]["body"] += "\nNote: issue modifiée"
        with self.assertRaisesRegex(registry.RegistryError, "périmée"):
            self.registry.claim(registry.STATE_REPO, 94, "Codex")

    def test_write_allowlist_refuses_any_other_branch_or_directory(self):
        for path, branch in [
            ("config/key.json", "orchestration/state"),
            ("registry/agents/foo.json", "main"),
            ("registry/../secret.json", "orchestration/state"),
            ("registry/tasks/nope.py", "orchestration/state"),
        ]:
            with self.subTest(path=path, branch=branch):
                with self.assertRaises(registry.RegistryError):
                    registry.validate_path(path, branch)

    def test_never_publish_approval_through_registry(self):
        self.registry.claim(registry.STATE_REPO, 94, "Codex")
        self.assertTrue(all("comments" not in endpoint for method, endpoint in self.fake.writes))
        self.assertEqual(len([m for m, _ in self.fake.writes if m == "PATCH"]), 1)

    def test_approval_phone_uppercase_and_trailing_space(self):
        issue = self.fake.issues[(registry.STATE_REPO, 94)]
        digest = dispatcher.approval_fingerprint(issue["body"], ["orchestration/"])
        comments = [{"user": {"login": "vadorus"}, "author_association": "OWNER",
                     "performed_via_github_app": None,
                     "body": "/autoriser " + digest[:12].upper() + " "}]
        self.assertTrue(dispatcher.verified_approval(issue, ["orchestration/"], comments))
        comments[0]["body"] = "/autoriser " + digest[:11].upper() + " "
        self.assertFalse(dispatcher.verified_approval(issue, ["orchestration/"], comments))


if __name__ == "__main__":
    unittest.main()
