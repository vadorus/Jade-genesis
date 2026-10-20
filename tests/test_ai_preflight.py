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
    def workflow_scripts():
        lines = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8").splitlines()
        positions = [i for i, line in enumerate(lines) if line == "          script: |"]
        sources = []
        for position in positions:
            block = []
            for line in lines[position + 1:]:
                if line.strip() and not line.startswith("            "):
                    break
                block.append(line[12:] if line.startswith("            ") else "")
            sources.append("\n".join(block))
        return sources

    @staticmethod
    def workflow_script():
        return ReservationOverlapTests.workflow_scripts()[0]

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_workflow_script_syntax(self):
        for source in self.workflow_scripts():
            with self.subTest(source=source[:75]):
                result = subprocess.run(["node", "--check", "--input-type=module"],
                                        input=source, text=True,
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

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_issue_event_invalidation_classifier(self):
        script = self.workflow_scripts()[1]
        self.assertIn("function reservationChange(payload)", script)
        functions = script[:script.index("if (reservationChange(context.payload)) {")]
        assertions = """
const assert = require('node:assert/strict');
assert.equal(reservationChange({action:'edited', issue:{body:'Réservation-état: En cours'}}), true);
assert.equal(reservationChange({action:'edited', issue:{body:'Suivi simple'},
  changes:{body:{from:'Réservation-état: Réservée'}}}), true);
assert.equal(reservationChange({action:'closed', issue:{body:'Réservation-état: En cours'}}), true);
assert.equal(reservationChange({action:'deleted', issue:{}}), true);
assert.equal(reservationChange({action:'transferred', issue:{}}), true);
assert.equal(reservationChange({action:'edited', issue:{body:'Simple issue'}}), false);
assert.equal(reservationChange({action:'edited', issue:{body:'Note: Réservation-état: En cours'}}), false);
"""
        result = subprocess.run(["node", "-"], input=functions + "\n" + assertions,
                                text=True, capture_output=True, timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_issue_event_invalidation_is_separate_and_privileged(self):
        source = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8")
        self.assertIn("types: [opened, edited, deleted, transferred, closed, reopened]", source)
        self.assertIn("if: github.event_name == 'issues'", source)
        self.assertIn("if: github.event_name == 'pull_request_target'", source)
        self.assertIn("check.app?.id !== 15368", source)
        self.assertIn("conclusion: 'failure'", source)
        self.assertEqual(len(self.workflow_scripts()), 2)

    def test_simulated_concurrent_reservations(self):
        cases = [
            (["scripts/"], "scripts/worker.py", True),
            (["scripts"], "scripts/worker.py", True),
            (["Scripts"], "scripts/worker.py", True),
            (["scripts/ai/"], "scripts/ai/other.py", True),
            (["scripts/"], "scripts-other/file.py", False),
            (["scripts/a.py"], "scripts/a.py.bak", False),
        ]
        for scope, filename, blocked in cases:
            with self.subTest(scope=scope, filename=filename):
                errors = preflight.conflicting_reservations(
                    1, [filename], [issue(2, zones=",".join(scope))])
                self.assertEqual(bool(errors), blocked)

    def test_workflow_rechecks_reservation_snapshot(self):
        source = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8")
        self.assertIn("issueSignature(reservationsBefore) !== issueSignature(reservationsAfter)", source)


if __name__ == "__main__":
    unittest.main()
