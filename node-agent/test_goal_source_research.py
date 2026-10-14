"""No-network tests for bounded Jade goal-to-source investigation."""
from __future__ import annotations
import hashlib
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

from goal_source_research import (
    _BlockExtractor, extract_passages, investigate_goal, select_sources,
)
from official_source_capture import APPROVED


class FakeHttp:
    def __init__(self, req, text=None, *, status=200, mime="text/html"):
        self.url = req.full_url
        self.content = io.BytesIO((text or (
            "<html><body><main>"
            "<h1>Export Blender objects as FBX for Roblox Studio</h1>"
            "<p>Export Blender mesh as FBX in the Roblox Studio importer. "
            "Check axis orientation and maintain appropriate model scale.</p>"
            "<p>FBX materials may not look identical after Roblox Studio import. "
            "Review imported texture and material settings.</p>"
            "<p>Some shader features are not supported in the Roblox importer.</p>"
            "</main></body></html>"
        )).encode("utf-8"))
        self.status = status
        self.headers = {"Content-Type": mime, "Content-Encoding": "identity"}

    def read(self, size):
        return self.content.read(size)

    def geturl(self):
        return self.url

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.content.close()


class GoalResearchTests(unittest.TestCase):
    def goal(self):
        return "Créer un arbre dans Blender pour Roblox Studio, exporter en FBX avec textures."

    def test_goal_selects_relevant_approved_sources(self):
        selected, reason = select_sources(self.goal())
        self.assertEqual(selected, ["roblox_blender", "roblox_export", "blender_fbx"])
        self.assertEqual(reason, "APPROVED_DOCS_SELECTED")
        self.assertTrue(all(x in APPROVED for x in selected))

    def test_only_blender_fbx_does_not_fetch_roblox(self):
        selected, reason = select_sources("Exporter un mesh FBX avec Blender")
        self.assertEqual(selected, ["blender_fbx"])
        self.assertEqual(reason, "APPROVED_DOCS_SELECTED")

    def test_unknown_domains_trigger_zero_network_requests(self):
        calls = []
        def opener(req, timeout):
            calls.append(req.full_url)
            raise AssertionError("Unknown goal must not invoke the network")
        report = investigate_goal("Diagnostic de la pompe à eau automobile", opener=opener)
        self.assertEqual(report["selection_status"], "NO_SUPPORTED_TOPIC")
        self.assertEqual(report["sources"], [])
        self.assertEqual(calls, [])

    def test_invalid_goals_are_refused_without_network(self):
        for value in ("", "   ", "a" * 321):
            with self.subTest(value=value[:10]), self.assertRaises(ValueError):
                investigate_goal(value, opener=lambda *args: self.fail("network called"))

    def test_collects_exact_source_hashes_and_no_body(self):
        seen = []
        def opener(req, timeout):
            seen.append(req.full_url)
            return FakeHttp(req)
        report = investigate_goal(self.goal(), opener=opener, now_ms=1000)
        self.assertEqual(len(seen), 3)
        self.assertEqual(len(report["sources"]), 3)
        self.assertEqual(report["errors"], [])
        self.assertFalse(report["automatic_verification"])
        self.assertFalse(report["automatic_promotion"])
        self.assertFalse(report["contradictions_proven"])
        for source in report["sources"]:
            self.assertFalse(source["body_persisted"])
            self.assertFalse(source["fact_verified"])
            self.assertFalse(source["inspected"])
            self.assertNotIn("raw", source)
            self.assertTrue(source["sha256"])
            self.assertTrue(source["source_url"].startswith("https://"))
            for excerpt in source["excerpts"]:
                self.assertEqual(
                    excerpt["excerpt_sha256"],
                    hashlib.sha256(excerpt["excerpt"].encode("utf-8")).hexdigest(),
                )
                self.assertEqual(excerpt["verification"], "NOT_VERIFIED")
                self.assertEqual(excerpt["stance"], "UNKNOWN")
                self.assertTrue(excerpt["untrusted_external_text"])
                self.assertLessEqual(len(excerpt["excerpt"]), 380)

    def test_script_markup_and_nav_are_not_source_passages(self):
        data = (
            "<html><head><script>Roblox Blender malicious injected docs script javascript</script></head>"
            "<body><nav><p>Roblox Blender navigation information ignored</p></nav>"
            "<main><p>To export an FBX mesh from Blender for Roblox Studio, "
            "use an appropriate scale and check your materials.</p></main>"
            "</body></html>"
        ).encode()
        passages = extract_passages(data, mime="text/html", query="Blender Roblox FBX")
        self.assertEqual(len(passages), 1)
        self.assertNotIn("malicious", passages[0]["excerpt"])
        self.assertNotIn("navigation", passages[0]["excerpt"])

    def test_possible_restriction_is_not_called_disproof(self):
        raw = (
            "<p>The Blender shader nodes are not supported when exporting FBX "
            "textures to Roblox Studio in some cases.</p>"
        ).encode()
        found = extract_passages(raw, mime="text/html", query="Blender Roblox FBX textures")
        self.assertEqual(len(found), 1)
        self.assertTrue(found[0]["potential_limitation"])
        self.assertEqual(found[0]["stance"], "UNKNOWN")
        report = investigate_goal(
            self.goal(), opener=lambda req, timeout: FakeHttp(
                req, text=raw.decode()
            )
        )
        self.assertTrue(all(
            s["possible_caveats"] and
            s["possible_caveats"][0]["review_status"] == "POTENTIAL_RESTRICTION_NOT_PROVEN"
            for s in report["sources"]
        ))

    def test_false_claim_in_document_is_not_promoted(self):
        raw = (
            "<p>Blender Roblox FBX can do absolutely everything without problems "
            "and textures always work perfectly on all devices.</p>"
        ).encode()
        found = extract_passages(raw, mime="text/html", query="Blender Roblox FBX")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["verification"], "NOT_VERIFIED")
        self.assertFalse(found[0]["inspected"])

    def test_html_malformed_and_self_closing_tags_do_not_crash(self):
        parser = _BlockExtractor()
        parser.feed(
            "<main><p>Roblox FBX mesh may use various supported export options.<br/>"
            "It needs validation after import.</p><p>Another paragraph"
        )
        self.assertTrue(parser.blocks)

    def test_source_network_failure_is_explicit_not_fabricated(self):
        def opener(req, timeout):
            if "docs.blender.org" in req.full_url:
                raise urllib.error.URLError("offline")
            return FakeHttp(req)
        report = investigate_goal(self.goal(), opener=opener)
        self.assertEqual(len(report["sources"]), 2)
        self.assertEqual(len(report["errors"]), 1)
        self.assertEqual(report["errors"][0]["source_key"], "blender_fbx")
        self.assertFalse(report["automatic_verification"])

    def test_empty_relevant_passages_remains_unverified(self):
        text = "<p>" + "Unrelated information about flowers and astronomy. " * 4 + "</p>"
        report = investigate_goal(self.goal(), opener=lambda req, timeout: FakeHttp(req, text=text))
        self.assertEqual(len(report["sources"]), 3)
        self.assertTrue(all(len(s["excerpts"]) == 0 for s in report["sources"]))
        self.assertFalse(report["automatic_verification"])

    def test_excerpt_dedup_and_selection_limit(self):
        block = "<p>Export your Blender FBX mesh to Roblox Studio with textures and materials.</p>"
        raw = ("<body>" + block * 30 + "</body>").encode()
        found = extract_passages(raw, mime="text/html", query="Blender Roblox FBX")
        self.assertEqual(len(found), 1)

    def test_report_counterchecks_are_questions_not_proofs(self):
        report = investigate_goal(self.goal(), opener=lambda req, timeout: FakeHttp(req))
        self.assertTrue(any("Roblox Studio" in q for q in report["countercheck_questions"]))
        self.assertFalse(report["contradictions_proven"])
        self.assertFalse(report["tool_execution"])


class NightLearningIntegrationTests(unittest.TestCase):
    def authorization(self, goal):
        from research_authorization import ResearchAuthorizationStore, official_collection_plan
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        store = ResearchAuthorizationStore(Path(temporary.name) / "audit.json")
        grant = store.grant(official_collection_plan(goal), reviewer="Synthetic test reviewer",
                            note="Synthetic network test with a fake HTTP opener", confirmed=True)
        return {"authorization_store": store, "authorization_id": grant["authorization_id"]}

    def test_plan_is_offline_and_requires_permission(self):
        from night_learning_lab import NightLearningLab
        lab = NightLearningLab()
        plan = lab.plan_official_goal_research(
            "Importer un maillage Blender dans Roblox en FBX"
        )
        self.assertFalse(plan["network_used"])
        self.assertTrue(plan["approval_required"])
        self.assertEqual(len(plan["sources"]), 3)

    def test_research_refuses_to_fetch_without_authorization(self):
        from night_learning_lab import NightLearningLab
        lab = NightLearningLab()
        with self.assertRaises(PermissionError):
            lab.research_official_goal(
                "Importer FBX Blender dans Roblox",
                opener=lambda req, timeout: self.fail("Network forbidden")
            )

    def test_approved_research_uses_only_official_allowlist(self):
        from night_learning_lab import NightLearningLab
        lab = NightLearningLab()
        seen = []
        def opener(req, timeout):
            seen.append(req.full_url)
            return FakeHttp(req)
        result = lab.research_official_goal(
            "Importer FBX Blender dans Roblox",
            **self.authorization("Importer FBX Blender dans Roblox"), opener=opener, now_ms=5000
        )
        self.assertEqual(len(seen), 3)
        self.assertEqual(len(result["sources"]), 3)
        self.assertFalse(result["automatic_promotion"])

    def test_out_of_domain_approved_research_does_not_use_network(self):
        from night_learning_lab import NightLearningLab
        lab = NightLearningLab()
        result = lab.research_official_goal(
            "Réparer une pompe de voiture",
            **self.authorization("Réparer une pompe de voiture"),
            opener=lambda req, timeout: self.fail("Unapproved network")
        )
        self.assertEqual(result["selection_status"], "NO_SUPPORTED_TOPIC")

    def test_legacy_boolean_cannot_grant_research_permission(self):
        from night_learning_lab import NightLearningLab
        with self.assertRaises(PermissionError):
            NightLearningLab().research_official_goal(
                "Importer FBX Blender dans Roblox", approved=True,
                opener=lambda *args: self.fail("Network forbidden"))

    def test_cli_without_recorded_authorization_never_fetches(self):
        from goal_source_research import main
        with patch("sys.argv", ["goal_source_research", "--goal", "Importer FBX Blender", "--out", "unused.json"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                with patch("goal_source_research.investigate_goal", side_effect=AssertionError("Network called")):
                    main()

    def test_changed_goal_and_reused_grant_refused_before_network(self):
        from night_learning_lab import NightLearningLab
        from research_authorization import AuthorizationRejected
        lab = NightLearningLab()
        auth = self.authorization("Importer FBX Blender dans Roblox")
        with self.assertRaises(AuthorizationRejected):
            lab.research_official_goal("Exporter FBX Blender", **auth,
                                      opener=lambda *args: self.fail("Network forbidden"))
        lab.research_official_goal("Importer FBX Blender dans Roblox", **auth,
                                  opener=lambda req, timeout: FakeHttp(req))
        with self.assertRaises(AuthorizationRejected):
            lab.research_official_goal("Importer FBX Blender dans Roblox", **auth,
                                      opener=lambda *args: self.fail("Network forbidden"))


if __name__ == "__main__":
    unittest.main()
