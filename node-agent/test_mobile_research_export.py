import json
from pathlib import Path
import tempfile
import unittest
from mobile_research_export import export_mobile_dossier
from test_goal_evidence_reasoner import research, probe_payload
from goal_evidence_reasoner import RejectedEvidence


class MobileResearchExportTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.source = self.root / "source.json"
        self.source.write_text(json.dumps(research()), encoding="utf-8")
        self.fbx = self.root / "test.fbx"
        blob = b"Kaydara FBX Binary  " + b"\0" * 150
        self.fbx.write_bytes(blob)
        self.report = self.root / "report.json"
        self.report.write_text(json.dumps(probe_payload(blob)), encoding="utf-8")

    def test_export_contains_displayable_passages_and_exact_artifacts_without_permissions(self):
        result = export_mobile_dossier(self.source, [(self.report, self.fbx)])
        self.assertEqual(result["source_texts"][0]["passages"][0]["text"], research()["sources"][0]["excerpts"][0]["excerpt"])
        self.assertEqual(len(result["local_artifacts"]), 1)
        self.assertFalse(result["action_authorized"])
        self.assertFalse(result["verified"])

    def test_modified_fbx_cannot_be_exported_as_checked_evidence(self):
        self.fbx.write_bytes(self.fbx.read_bytes() + b"tampered")
        with self.assertRaises(RejectedEvidence):
            export_mobile_dossier(self.source, [(self.report, self.fbx)])
