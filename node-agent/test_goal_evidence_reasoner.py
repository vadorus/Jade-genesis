"""Synthetic adversarial tests for offline document/experiment reasoning."""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from goal_evidence_reasoner import (
    RejectedEvidence, document_references, inspect_local_probe, review_goal_evidence,
)


def research():
    snippet = "FBX Blender geometry imports may require scale configuration in Roblox Studio."
    return {
        "schema": "jade.goal_source_research.v1",
        "goal": "Import FBX Blender into Roblox Studio",
        "automatic_verification": False,
        "automatic_promotion": False,
        "contradictions_proven": False,
        "tool_execution": False,
        "sources": [{
            "source_key": "roblox_blender",
            "source_url": "https://create.roblox.com/docs/art/blender",
            "sha256": hashlib.sha256(b"source").hexdigest(),
            "inspected": False,
            "fact_verified": False,
            "assessment": "UNASSESSED",
            "body_persisted": False,
            "direction": "UNKNOWN",
            "excerpts": [{
                "excerpt": snippet,
                "excerpt_sha256": hashlib.sha256(snippet.encode()).hexdigest(),
                "block_index": 23,
                "inspected": False,
                "stance": "UNKNOWN",
                "verification": "NOT_VERIFIED",
                "untrusted_external_text": True,
                "potential_limitation": True,
            }]
        }],
    }


def probe_payload(fbx):
    return {
        "schema": "jade.local_blender_fbx_probe.v1",
        "status": "PASS",
        "blender_version": "5.2.2 LTS",
        "executed_at_ms": 1791831731346,
        "experiment": "synthetic FBX roundtrip inside Blender only",
        "fbx_bytes": len(fbx),
        "fbx_sha256": hashlib.sha256(fbx).hexdigest(),
        "geometry_before": [
            {"name": "SyntheticTrunk", "vertices": 24, "triangles": 44,
             "dimensions": [0.46, 0.46, 2.0]},
            {"name": "SyntheticCanopy", "vertices": 24, "triangles": 44,
             "dimensions": [1.8, 1.8, 2.4]},
        ],
        "geometry_after": [
            {"name": "SyntheticCanopy", "vertices": 24, "triangles": 44,
             "dimensions": [1.8, 1.8, 2.4]},
            {"name": "SyntheticTrunk", "vertices": 24, "triangles": 44,
             "dimensions": [0.46, 0.46, 2.0]},
        ],
        "validations": {
            "two_mesh_objects": True,
            "vertex_count_preserved": True,
            "triangles_preserved": True,
            "bounding_dimensions_preserved": True,
        }
    }


class EvidenceReasoningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.fbx = b"Kaydara FBX Binary  " + b"\x00" * 150
        self.fbx_path = self.root / "fixture.fbx"
        self.fbx_path.write_bytes(self.fbx)
        self.report_path = self.root / "report.json"
        self._write(probe_payload(self.fbx))

    def _write(self, report):
        self.report_path.write_text(json.dumps(report), encoding="utf-8")

    def test_valid_sources_remain_uninspected(self):
        goal_hash, sources = document_references(research())
        self.assertEqual(len(goal_hash), 64)
        self.assertEqual(len(sources), 1)
        self.assertEqual(len(sources[0]["excerpts"]), 1)
        self.assertTrue(sources[0]["caveat_excerpt_hashes"])
        self.assertFalse(sources[0]["inspected"])
        self.assertEqual(sources[0]["evidence_stance"], "UNASSESSED")

    def test_source_digest_tampering_rejected(self):
        bad = research()
        bad["sources"][0]["excerpts"][0]["excerpt"] += " manipulated"
        with self.assertRaisesRegex(RejectedEvidence, "Snippet digest mismatch"):
            document_references(bad)

    def test_repeated_excerpt_cannot_inflate_evidence(self):
        bad = research()
        bad["sources"][0]["excerpts"].append(copy.deepcopy(bad["sources"][0]["excerpts"][0]))
        with self.assertRaisesRegex(RejectedEvidence, "Duplicate excerpt"):
            document_references(bad)

    def test_missing_report_is_rejected_as_evidence_error(self):
        with self.assertRaises(RejectedEvidence):
            inspect_local_probe(self.root / "missing.json", self.fbx_path)

    def test_fake_verified_source_rejected(self):
        bad = research()
        bad["sources"][0]["inspected"] = True
        with self.assertRaises(RejectedEvidence):
            document_references(bad)
        bad = research()
        bad["automatic_promotion"] = True
        with self.assertRaises(RejectedEvidence):
            document_references(bad)

    def test_mismatched_source_url_and_duplicate_refused(self):
        bad = research()
        bad["sources"][0]["source_url"] = "https://attacker.invalid/doc"
        with self.assertRaises(RejectedEvidence):
            document_references(bad)
        bad = research()
        bad["sources"].append(copy.deepcopy(bad["sources"][0]))
        with self.assertRaises(RejectedEvidence):
            document_references(bad)

    def test_untrusted_html_cannot_authorize_execution(self):
        bad = research()
        bad["tool_execution"] = True
        with self.assertRaises(RejectedEvidence):
            document_references(bad)

    def test_local_probe_has_exact_sha_and_expected_geometry(self):
        out = inspect_local_probe(self.report_path, self.fbx_path)
        self.assertTrue(out["local_checks_passed"])
        self.assertFalse(out["trusted_external_attestation"])
        self.assertEqual(out["scope"], "BLENDER_ONLY_NOT_ROBLOX")
        self.assertEqual(out["fbx_sha256"], hashlib.sha256(self.fbx).hexdigest())

    def test_modified_fbx_is_rejected(self):
        self.fbx_path.write_bytes(self.fbx + b"tampered")
        with self.assertRaisesRegex(RejectedEvidence, "size mismatch"):
            inspect_local_probe(self.report_path, self.fbx_path)

    def test_forged_topology_is_rejected(self):
        report = probe_payload(self.fbx)
        report["geometry_after"][0]["triangles"] = 2
        self._write(report)
        with self.assertRaises(RejectedEvidence):
            inspect_local_probe(self.report_path, self.fbx_path)

    def test_wrong_status_false_validator_and_version_rejected(self):
        for field, value in (
            ("status", "FAIL"),
            ("blender_version", "3.6"),
            ("experiment", "download unknown assets"),
        ):
            altered = probe_payload(self.fbx)
            altered[field] = value
            self._write(altered)
            with self.subTest(field=field), self.assertRaises(RejectedEvidence):
                inspect_local_probe(self.report_path, self.fbx_path)
        altered = probe_payload(self.fbx)
        altered["validations"]["triangles_preserved"] = False
        self._write(altered)
        with self.assertRaises(RejectedEvidence):
            inspect_local_probe(self.report_path, self.fbx_path)

    def test_generated_review_never_claims_roblox_or_promotion(self):
        checked = inspect_local_probe(self.report_path, self.fbx_path)
        result = review_goal_evidence(research(), checked_probes=[checked])
        self.assertEqual(result["night_review_status"], "REQUIRES_HUMAN_EVIDENCE_INSPECTION")
        self.assertFalse(result["automatic_promotion"])
        self.assertFalse(result["evidence_ledger_import_allowed"])
        self.assertFalse(result["reasoning"]["roblox_studio_import_tested"])
        self.assertTrue(result["reasoning"]["blender_workflow_tested_locally"])
        self.assertEqual(result["reasoning"]["potential_caveat_count"], 1)
        self.assertEqual(result["next_experiments"][0]["status"], "NOT_EXECUTED")
        self.assertNotIn("Import FBX Blender", json.dumps(result))
        self.assertNotIn("FBX Blender geometry imports may require", json.dumps(result))

    def test_no_experiment_results_remain_unverified(self):
        result = review_goal_evidence(research())
        self.assertEqual(result["reasoning"]["blender_local_replays_checked"], 0)
        self.assertFalse(result["reasoning"]["blender_workflow_tested_locally"])
        self.assertFalse(result["reasoning"]["new_knowledge_verified"])

    def test_duplicate_artefact_is_not_second_replay(self):
        test = inspect_local_probe(self.report_path, self.fbx_path)
        with self.assertRaisesRegex(RejectedEvidence, "Repeated FBX"):
            review_goal_evidence(research(), checked_probes=[test, test])

    def test_attestation_cannot_be_self_declared(self):
        checked = inspect_local_probe(self.report_path, self.fbx_path)
        checked["trusted_external_attestation"] = True
        with self.assertRaises(RejectedEvidence):
            review_goal_evidence(research(), checked_probes=[checked])

    def test_night_learning_lab_consumes_review_artifacts_offline(self):
        from night_learning_lab import NightLearningLab
        source_path = self.root / "research.json"
        source_path.write_text(json.dumps(research()), encoding="utf-8")
        lab = NightLearningLab()
        outcome = lab.review_official_goal_artifacts(
            source_path, [(self.report_path, self.fbx_path)]
        )
        self.assertEqual(outcome["reasoning"]["blender_local_replays_checked"], 1)
        self.assertFalse(outcome["automatic_tool_execution"])
        self.assertFalse(outcome["evidence_ledger_import_allowed"])
        self.assertFalse(outcome["reasoning"]["new_knowledge_verified"])

    def test_oversized_probe_blocked_before_parsing(self):
        self.report_path.write_text(" " * 12_001, encoding="utf-8")
        with self.assertRaises(RejectedEvidence):
            inspect_local_probe(self.report_path, self.fbx_path)


if __name__ == "__main__":
    unittest.main()
