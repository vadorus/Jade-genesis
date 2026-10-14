import base64
import copy
import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from jade_learning_review import export_learning_review, learning_review_state
from local_research_review import LocalResearchReview, ReviewServer
from night_learning_lab import NightLearningLab
from research_authorization import AuthorizationRejected


class NoExternalResearch:
    def search(self, query):
        raise AssertionError("The local Jade workshop must never call a research provider")


def jade_fixture():
    view = {"revision": 42, "entities": [{"event_id": "synthetic-runtime", "kind": "runtime_eval_snapshot",
            "entity_id": "current", "created_at": 1000, "payload": json.dumps({
                "observation_count": 5, "confidence": 0.3, "score": 70.0, "groups": []})}]}
    return NightLearningLab(NoExternalResearch()).draft_local_learning_review(
        view, now_ms=2000, goal="TEST synthétique : examiner les propositions d'amélioration de Jade")


def report_fixture():
    return json.loads(base64.b64decode(jade_fixture()["night_report_base64"]))


class JadeLearningReviewTests(unittest.TestCase):
    def test_offline_draft_preserves_jade_hypotheses_and_criteria_without_external_calls(self):
        bundle = jade_fixture(); state = learning_review_state(bundle)
        report = json.loads(base64.b64decode(bundle["night_report_base64"]))
        self.assertFalse(report["external_research_attempted"])
        self.assertGreater(len(state["packet"]["hypotheses"]), 0)
        self.assertEqual(state["packet"]["next_experiments"][0]["success_criterion"], report["hypotheses"][0]["success_criterion"])
        self.assertEqual(state["scope"], "JADE_SELF_IMPROVEMENT")
        self.assertEqual(state["packet"]["experiment_artifacts"], [])
        self.assertNotIn("roblox", json.dumps(bundle).lower())
        self.assertNotIn("blender", json.dumps(state).lower())

    def test_report_bytes_and_all_permission_flags_are_checked(self):
        bundle = jade_fixture()
        for flag in ("verified", "action_authorized", "automatic_promotion"):
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                learning_review_state({**bundle, flag: True})
        with self.assertRaises(ValueError):
            learning_review_state({**bundle, "night_report_sha256": "0" * 64})
        with self.assertRaises(ValueError):
            learning_review_state({**bundle, "authorization_id": "injected"})

    def test_model_report_cannot_enable_execution_promotion_or_use_raw_conversation(self):
        report = report_fixture()
        for flag in ("automatic_experiment_execution", "automatic_promotion", "production_code_rewrite",
                     "shell_execution", "raw_conversation_text_used", "user_feedback_promoted_to_external_fact"):
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                export_learning_review({**report, flag: True})

    def test_experiment_needs_its_hypothesis_and_frozen_paired_sample_limits(self):
        original = report_fixture()
        for key, value in (("hypothesis_id", "absent"), ("automatic_execution", True),
                           ("requires_explicit_promotion_approval", False), ("paired_scenarios_required", False),
                           ("frozen_champion_required", False), ("minimum_samples_per_side", 1.5),
                           ("maximum_samples_per_side", 101)):
            report = copy.deepcopy(original); report["experiments"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): export_learning_review(report)

    def test_duplicate_identity_and_fabricated_hypothesis_promotion_are_rejected(self):
        report = report_fixture(); report["hypotheses"].append(copy.deepcopy(report["hypotheses"][0]))
        with self.assertRaises(ValueError): export_learning_review(report)
        report = report_fixture(); report["hypotheses"][0]["promoted_as_external_fact"] = True
        with self.assertRaises(ValueError): export_learning_review(report)

    def test_nonfinite_observations_and_oversized_report_are_rejected(self):
        for value in (float("nan"), float("inf"), -0.1, True):
            report = report_fixture(); report["runtime_confidence"] = value
            with self.subTest(value=value), self.assertRaises(ValueError): export_learning_review(report)
        report = report_fixture(); report["extra"] = "x" * 300001
        with self.assertRaises(ValueError): export_learning_review(report)


class GeneralJadeServiceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name); (self.root / "node-agent").mkdir(); (self.root / "capture_local").mkdir()
        self.service = LocalResearchReview(self.root)

    def import_report(self):
        return self.service.mutate("import-learning", {"snapshot_sha256": self.service.state()["snapshot_sha256"], "report": jade_fixture()})

    def permission_body(self):
        state = self.service.state(); item = state["plans"][0]
        return {"snapshot_sha256": state["snapshot_sha256"], "action_id": item["plan"]["action_id"],
                "plan_sha256": item["plan_sha256"], "reviewer": "Synthetic test", "note": "TEST recheck only", "confirmed": True}

    def test_default_is_empty_jade_workshop_without_a_hardcoded_game_goal(self):
        state = self.service.state()
        self.assertFalse(state["ready"]); self.assertEqual(state["plans"], [])
        self.assertEqual(state["packet"]["next_experiments"], [])
        self.assertEqual(state["events"], [])
        self.assertNotIn("roblox", json.dumps(state).lower())

    def test_import_and_export_never_authorize_or_launch_proposed_targets(self):
        with patch("subprocess.run", side_effect=AssertionError("Process launched")), \
                patch("goal_source_research.investigate_goal", side_effect=AssertionError("Network called")):
            self.import_report(); state = self.service.state()
            exported = self.service.mutate("export-mobile", {"snapshot_sha256": state["snapshot_sha256"]})
        self.assertFalse(exported["permission_exported"])
        self.assertEqual(json.loads(exported["dossier_json"]), jade_fixture())
        self.assertEqual(exported["dossier_sha256"], hashlib.sha256(exported["dossier_json"].encode()).hexdigest())
        self.assertEqual([p["plan"]["action_id"] for p in state["plans"]], ["OFFLINE_ARTIFACT_RECHECK"])
        self.assertEqual(state["events"], [])

    def test_proposal_id_is_never_an_executable_action(self):
        self.import_report(); body = self.permission_body()
        proposal = self.service.state()["packet"]["next_experiments"][0]
        for identifier in (proposal["id"], "OFFICIAL_SOURCE_COLLECTION", "BLENDER_SYNTHETIC_FBX_ROUNDTRIP"):
            with self.subTest(identifier=identifier), self.assertRaises(AuthorizationRejected):
                self.service.mutate("authorize", {**body, "action_id": identifier})
        self.assertEqual(self.service.store.events(), [])

    def test_recheck_is_gated_one_use_and_does_not_test_a_jade_modification(self):
        self.import_report(); body = self.permission_body()
        with self.assertRaises(AuthorizationRejected): self.service.mutate("execute", body)
        grant = self.service.mutate("authorize", body)
        request = {**body, "authorization_id": grant["authorization_id"]}
        with patch("subprocess.run", side_effect=AssertionError("Process launched")):
            result = self.service.mutate("execute", request)
        self.assertEqual(result["result"]["scope"], "JADE_SELF_IMPROVEMENT")
        self.assertFalse(result["knowledge_verified"])
        self.assertEqual(self.service.result(grant["authorization_id"])["result"], result["result"])
        with self.assertRaises(AuthorizationRejected): self.service.mutate("execute", request)

    def test_replaced_import_preserves_previous_bytes_and_invalidates_old_plan(self):
        self.import_report(); body = self.permission_body(); self.service.mutate("authorize", body)
        previous = self.service.research.read_bytes()
        changed = jade_fixture(); changed["goal"] = "TEST autre objectif de Jade"
        self.service.mutate("import-learning", {"snapshot_sha256": body["snapshot_sha256"], "report": changed})
        archives = list((self.root / "capture_local/review_imports").glob("*/previous_dossier.json"))
        self.assertEqual(len(archives), 1); self.assertEqual(archives[0].read_bytes(), previous)
        with self.assertRaises(AuthorizationRejected): self.service.mutate("authorize", body)

    def test_bad_import_does_not_replace_current_review(self):
        self.import_report(); previous = self.service.research.read_bytes()
        report = report_fixture(); report["shell_execution"] = True
        with self.assertRaises(ValueError):
            self.service.mutate("import-learning", {"snapshot_sha256": self.service.state()["snapshot_sha256"], "report": report})
        self.assertEqual(self.service.research.read_bytes(), previous)


class GeneralJadeHttpTests(unittest.TestCase):
    def test_import_requires_same_origin_token_and_current_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "node-agent").mkdir(); (root / "capture_local").mkdir()
            service = LocalResearchReview(root); server = ReviewServer(service)
            worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
            try:
                headers = {"Content-Type": "application/json", "X-Jade-Review": server.csrf, "Origin": server.origin}
                body = json.dumps({"snapshot_sha256": service.state()["snapshot_sha256"], "report": jade_fixture()})
                for origin, token, expected in (("https://foreign.invalid", server.csrf, 403), (server.origin, "fake", 403), (server.origin, server.csrf, 200)):
                    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                    connection.request("POST", "/api/import-learning", body, {**headers, "Origin": origin, "X-Jade-Review": token})
                    response = connection.getresponse(); self.assertEqual(response.status, expected); response.read(); connection.close()
                self.assertTrue(service.state()["ready"]); self.assertEqual(service.store.events(), [])
            finally: server.shutdown(); server.server_close()
