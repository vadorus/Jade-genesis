"""Tests du précontrôle de coordination, sans réseau ni écriture GitHub."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import shutil
import subprocess
import unittest

REPO = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("ai_preflight", REPO / "scripts" / "ai_preflight.py")
assert SPEC and SPEC.loader
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


def issue(number, state="Réservée", zones="scripts/", open_state="open", pr=False):
    value = {"number": number, "state": open_state, "body": (
        "Réservation-état: " + state + "\nZones: " + zones
    )}
    if pr:
        value["pull_request"] = {}
    return value


class ReservationOverlapTests(unittest.TestCase):
    def test_exact_file_conflict(self):
        self.assertTrue(preflight.zones_overlap(["scripts/logic.gd"], ["scripts/logic.gd"]))

    def test_exact_files_disjoint(self):
        self.assertFalse(preflight.zones_overlap(["scripts/a.gd"], ["scripts/b.gd"]))

    def test_bare_directory_reserves_descendants(self):
        self.assertTrue(preflight.zones_overlap(["scripts"], ["scripts/ai_preflight.py"]))
        self.assertTrue(preflight.zones_overlap(["scripts/ai_preflight.py"], ["scripts"]))
        self.assertTrue(preflight.zones_overlap(["scripts"], ["scripts/ai/"]))
        self.assertTrue(preflight.within("scripts/ai_preflight.py", ["scripts"]))

    def test_case_insensitive_reservation(self):
        self.assertTrue(preflight.zones_overlap(["Scripts/"], ["scripts/ai_preflight.py"]))
        self.assertTrue(preflight.zones_overlap(["SCRIPTS"], ["scripts/"]))
        self.assertTrue(preflight.within("scripts/Logic.gd", ["SCRIPTS/"]))

    def test_bare_scope_preserves_component_boundary(self):
        self.assertFalse(preflight.zones_overlap(["scripts"], ["scripts-extra/"]))
        self.assertFalse(preflight.within("scripts-old/file.gd", ["scripts"]))
        self.assertFalse(preflight.zones_overlap(["scripts/a.py"], ["scripts/a.py.bak"]))

    def test_bare_directory_conflicts_with_active_issue(self):
        self.assertEqual(len(preflight.conflicting_reservations(
            1, ["scripts/file.gd"], [issue(2, zones="scripts")])), 1)

    def test_folder_contains_file(self):
        self.assertTrue(preflight.zones_overlap(["scripts/"], ["scripts/logic.gd"]))

    def test_file_inside_folder_reverse(self):
        self.assertTrue(preflight.zones_overlap(["scripts/logic.gd"], ["scripts/"]))

    def test_nested_directories(self):
        self.assertTrue(preflight.zones_overlap(["scripts/"], ["scripts/ai/"]))

    def test_prefix_collision_is_not_overlap(self):
        self.assertFalse(preflight.zones_overlap(["scripts/"], ["scripts-extra/"]))
        self.assertFalse(preflight.zones_overlap(["scripts/a.gd"], ["scripts/a.gd.bak"]))

    def test_active_issue_without_pr_blocks(self):
        self.assertEqual(len(preflight.conflicting_reservations(
            1, ["scripts/ai/"], [issue(2, zones="scripts/")])), 1)

    def test_inactive_issue_ignored(self):
        self.assertEqual(preflight.conflicting_reservations(
            1, ["scripts/"], [issue(2, state="À qualifier")]), [])

    def test_own_issue_ignored(self):
        self.assertEqual(preflight.conflicting_reservations(
            1, ["scripts/"], [issue(1)]), [])

    def test_tracking_issue_ignored(self):
        self.assertEqual(preflight.conflicting_reservations(
            1, ["scripts/"], [{"number": 2, "state": "open", "body": "Suivi de coordination"}]), [])

    def test_malformed_active_issue_blocks(self):
        self.assertEqual(len(preflight.conflicting_reservations(
            1, ["scripts/"], [issue(2, zones="../secrets/")])), 1)

    def test_closed_issue_or_pr_ignored(self):
        self.assertEqual(preflight.conflicting_reservations(
            1, ["scripts/"], [issue(2, open_state="closed"), issue(3, pr=True)]), [])

    def test_preflight_checks_issue_snapshot_again(self):
        source = (REPO / "scripts" / "ai_preflight.py").read_text(encoding="utf-8")
        self.assertIn("later_issues =", source)
        self.assertIn("issue_signature(later_issues) != issue_signature(issues)", source)

    @staticmethod
    def workflow_script():
        lines = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8").splitlines()
        position = lines.index("          script: |")
        return "\n".join(line[12:] for line in lines[position + 1:] if line.startswith("            "))

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_workflow_script_syntax(self):
        result = subprocess.run(["node", "--check", "--input-type=module"],
                                input=self.workflow_script(), text=True,
                                capture_output=True, timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_workflow_js_zone_logic(self):
        script = self.workflow_script()
        functions = script[script.index("function zoneKey(value) {"):
                           script.index("function issueSignature(items) {")]
        assertions = """
const assert = require('node:assert/strict');
assert.equal(zonesOverlap(['scripts'], ['scripts/ai_preflight.py']), true);
assert.equal(zonesOverlap(['scripts/ai_preflight.py'], ['scripts']), true);
assert.equal(zonesOverlap(['Scripts/'], ['scripts/ai_preflight.py']), true);
assert.equal(zonesOverlap(['SCRIPTS'], ['scripts/']), true);
assert.equal(within('scripts/ai_preflight.py', ['scripts']), true);
assert.equal(within('scripts/Logic.gd', ['SCRIPTS/']), true);
assert.equal(zonesOverlap(['scripts'], ['scripts-extra/']), false);
assert.equal(zonesOverlap(['scripts/a.py'], ['scripts/a.py.bak']), false);
assert.equal(within('scripts-old/file.gd', ['scripts']), false);
"""
        result = subprocess.run(["node", "-"], input=functions + "\n" + assertions,
                                text=True, capture_output=True, timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_workflow_rechecks_reservation_snapshot(self):
        source = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8")
        self.assertIn("issueSignature(reservationsBefore) !== issueSignature(reservationsAfter)", source)


if __name__ == "__main__":
    unittest.main()
