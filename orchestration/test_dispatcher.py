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
        self.assertEqual(mod.github_claims(["vadorus/Jade-genesis"], [], token="test-token", fetch=fake), [])

    def test_synthetic_github_claims_trusted_conflict(self):
        def fake(url, headers):
            if "issues?" in url:
                return [{"number": 8, "user": {"login": "vadorus"}, "body": "Réservation-état: Réservée\nZones: orchestration/"}]
            return []
        claims = mod.github_claims(["vadorus/Jade-genesis"], [], token="test-token", fetch=fake)
        self.assertTrue(any(c["task_id"] == "issue:8" and c["zones"] == ["orchestration/"]
                            for c in claims))

    def test_synthetic_github_malformed_trusted_blocks(self):
        def fake(url, headers):
            if "issues?" in url:
                return [{"number": 8, "user": {"login": "vadorus"}, "body": "Réservation-état : Réservée\nZones: orchestration/"}]
            return []
        with self.assertRaises(mod.PlanningError):
            mod.github_claims(["vadorus/Jade-genesis"], [], token="test-token", fetch=fake)

    def test_synthetic_github_pr_files_conflict(self):
        def fake(url, headers):
            if "issues?" in url: return []
            if "pulls?" in url: return [{"number": 12}]
            if "/pulls/12/files?" in url: return [{"filename": "orchestration/a.py"}]
            if url.endswith("/pulls/12"): return {"number": 12, "changed_files": 1, "body": "", "head": {"ref": "codex/test", "repo": {"full_name": "vadorus/Jade-genesis"}}}
            raise AssertionError(url)
        claims = mod.github_claims(["vadorus/Jade-genesis"], [], token="test-token", fetch=fake)
        self.assertTrue(any(c["task_id"] == "pr:12" and c["zones"] == ["orchestration/a.py"]
                            for c in claims))

    def test_incomplete_pr_inventory_fails_closed(self):
        def fake(url, headers):
            if "issues?" in url: return []
            if "pulls?" in url: return [{"number": 12}]
            if "/pulls/12/files?" in url: return [{"filename": "orchestration/a.py"}]
            if url.endswith("/pulls/12"): return {"number": 12, "changed_files": 2, "body": "", "head": {"ref": "codex/test", "repo": {"full_name": "vadorus/Jade-genesis"}}}
            raise AssertionError(url)
        with self.assertRaises(mod.PlanningError):
            mod.github_claims(["vadorus/Jade-genesis"], [], token="test-token", fetch=fake)



class GithubAuthorityTests(unittest.TestCase):
    """Real GitHub REST response shapes: list PR lacks changed_files; detail has it."""

    def setUp(self):
        self.value = state()
        self.value["tasks"][0].update(id="#90", zones=["orchestration/a.py"],
                                      assigned_writer="Codex", authorized=True)
        self.issue = {
            "number": 90, "state": "open", "user": {"login": "vadorus"},
            "body": "Responsable-IA: Codex\nRelecteur-IA: Claude\n"
                    "Réservation-état: Réservée\nZones: orchestration/",
            "updated_at": "2026-10-21T09:20:00Z"
        }
        self.pr_detail = {
            "number": 91, "changed_files": 1, "state": "open",
            "body": "Tâche: #90\nAuteur-IA: Codex\nRelecteur-IA: Claude",
            "head": {"ref": "codex/orchestration-mvp-20261021",
                     "repo": {"full_name": "vadorus/Jade-genesis"}},
        }
        self.pr_list = [{"number": 91, "state": "open", "head": self.pr_detail["head"]}]
        self.files = {"91": [{"filename": "orchestration/a.py"}]}
        self.comments = []
        self.issues = [self.issue]

    def approve(self, *, author="vadorus", association="OWNER", body=None):
        digest = mod.approval_fingerprint(
            self.issue["body"], ["orchestration/"])
        self.comments = [{
            "id": 1,
            "body": body or "/autoriser\nsha256:" + digest,
            "user": {"login": author},
            "author_association": association,
            "created_at": "2026-10-21T10:00:00Z",
        }]

    def fake(self, url, headers):
        if "Authorization" not in headers:
            raise AssertionError("Expected token")
        if "/issues?" in url:
            return self.issues
        if "/pulls?" in url:
            return self.pr_list
        if "/issues/90/comments?" in url:
            return self.comments
        if url.endswith("/pulls/91"):
            return self.pr_detail
        if "/pulls/91/files?" in url:
            return self.files["91"]
        if url.endswith("/pulls/92"):
            return self.second_pr
        if "/pulls/92/files?" in url:
            return self.files["92"]
        raise AssertionError(url)

    def claims(self):
        return mod.github_claims(
            ["vadorus/Jade-genesis"], ["vadorus"],
            token="test-token", fetch=self.fake, tasks=self.value["tasks"])

    def test_self_pr_verified_and_excluded_only_when_fingerprint_matches(self):
        self.approve()
        claims = self.claims()
        self.assertTrue(next(c for c in claims if c["task_id"] == "pr:91")["verified_self"])
        self.assertEqual(len(mod.plan(self.value, claims)["assignments"]), 1)

    def test_foreign_pr_same_task_cannot_be_excluded(self):
        self.approve()
        self.pr_detail["head"]["ref"] = "antigravity/other-work"
        result = mod.plan(self.value, self.claims())
        self.assertEqual(result["blocked"][0]["reason"], "reserved_zone_conflict")

    def test_pr_from_fork_is_not_verified(self):
        self.approve()
        self.pr_detail["head"]["repo"]["full_name"] = "other/Jade-genesis"
        self.assertEqual(mod.plan(self.value, self.claims())["blocked"][0]["reason"],
                         "reserved_zone_conflict")

    def test_duplicate_task_prs_block_even_if_one_is_valid(self):
        self.approve()
        self.pr_list.append({"number": 92, "state": "open", "head": {"ref": "antigravity/other"}})
        self.second_pr = {
            "number": 92, "changed_files": 1,
            "body": "Tâche: #90\nAuteur-IA: Antigravity",
            "head": {"ref": "antigravity/other", "repo": {"full_name": "vadorus/Jade-genesis"}},
        }
        self.files["92"] = [{"filename": "orchestration/another.py"}]
        result = mod.plan(self.value, self.claims())
        self.assertEqual(result["blocked"][0]["reason"], "duplicate_pr_for_task")

    def test_real_api_list_without_changed_files_is_verified_using_detail(self):
        self.approve()
        self.assertNotIn("changed_files", self.pr_list[0])
        self.assertEqual(len(mod.plan(self.value, self.claims())["assignments"]), 1)

    def test_missing_changed_files_on_detail_fails_closed(self):
        self.pr_detail.pop("changed_files")
        with self.assertRaises(mod.PlanningError):
            self.claims()

    def test_incomplete_pr_files_fails_closed(self):
        self.pr_detail["changed_files"] = 2
        with self.assertRaises(mod.PlanningError):
            self.claims()

    def test_tracking_issue_word_in_sentence_is_ignored(self):
        self.approve()
        self.issues.append({
            "number": 87, "state": "open", "user": {"login": "vadorus"},
            "body": "Le mot Réservation-état apparaît dans cette phrase, sans réservation."
        })
        self.assertEqual(len(mod.plan(self.value, self.claims())["assignments"]), 1)

    def test_approval_absent_even_when_json_says_authorized(self):
        self.assertEqual(mod.plan(self.value, self.claims())["blocked"][0]["reason"],
                         "github_authorization_missing_or_stale")

    def test_approval_from_wrong_author_or_not_owner(self):
        for author, association in [("intruder", "OWNER"), ("vadorus", "CONTRIBUTOR")]:
            with self.subTest(author=author, association=association):
                self.approve(author=author, association=association)
                self.assertEqual(mod.plan(self.value, self.claims())["blocked"][0]["reason"],
                                 "github_authorization_missing_or_stale")

    def test_plain_command_not_provable(self):
        self.approve(body="/autoriser")
        self.assertEqual(mod.plan(self.value, self.claims())["blocked"][0]["reason"],
                         "github_authorization_missing_or_stale")

    def test_approval_stale_after_issue_body_change(self):
        self.approve()
        self.issue["body"] += "\nNote: périmètre modifié"
        self.assertEqual(mod.plan(self.value, self.claims())["blocked"][0]["reason"],
                         "github_authorization_missing_or_stale")

    def test_no_api_token_rejected_even_with_fake_fetch(self):
        with self.assertRaisesRegex(mod.PlanningError, "GITHUB_TOKEN"):
            mod.github_claims(["vadorus/Jade-genesis"], [], token=None,
                              fetch=self.fake, tasks=self.value["tasks"])

    def test_rate_limit_or_api_error_fails_closed(self):
        from urllib.error import HTTPError
        def quota_error(url, headers):
            raise HTTPError(url, 403, "API rate limit exceeded", None, None)
        with self.assertRaises(HTTPError):
            mod.github_claims(["vadorus/Jade-genesis"], [], token="test-token",
                              fetch=quota_error, tasks=self.value["tasks"])

    def test_gemini_review_allowed_only_for_low_risk_tasks(self):
        value = state()
        value["agents"][0]["quota_units"] = None
        value["tasks"][0].update(kind="documentation", zones=["docs/help.md"])
        result = mod.plan(value)
        self.assertEqual(result["assignments"][0]["reviewer"], "Antigravity")
        self.assertEqual(result["assignments"][0]["writer"], "Codex")

    def test_gemini_review_rejected_for_sensitive_paths(self):
        for kind, scope in [
            ("documentation", [".github/README.md"]),
            ("testing", ["scripts/ai_preflight.py"]),
            ("testing", ["orchestration/dispatcher.py"]),
            ("implementation", ["docs/help.md"]),
        ]:
            with self.subTest(scope=scope):
                value = state()
                value["agents"][0]["quota_units"] = None
                value["tasks"][0].update(kind=kind, zones=scope)
                result = mod.plan(value)
                self.assertEqual(result["assignments"], [])
                self.assertEqual(result["blocked"][0]["reason"],
                                 "reviewer_unavailable_or_quota_unknown")


if __name__ == "__main__":
    unittest.main()
