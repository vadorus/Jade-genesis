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
    value = {"number": number, "state": open_state, "user": {"login": "vadorus"}, "body": (
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
            1, ["scripts/"], [issue(2, state="Terminée")]), [])

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
        functions = script[script.index("function field(body, name) {"):
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
assert.equal(reservationChange({action:'edited', issue:{user:{login:'vadorus'},body:'Réservation-état: En cours'}}), true);
assert.equal(reservationChange({action:'edited', issue:{body:'Suivi simple'},
  changes:{body:{from:'Réservation-état: Réservée'}}}), false);
assert.equal(reservationChange({action:'closed', issue:{user:{login:'vadorus'}, body:'Réservation-état: En cours'}}), true);
assert.equal(reservationChange({action:'deleted', issue:{user:{login:'vadorus'}}}), true);
assert.equal(reservationChange({action:'transferred', issue:{user:{login:'vadorus'}}}), true);
assert.equal(reservationChange({action:'edited', issue:{user:{login:'vadorus'},body:'Simple issue'}}), false);
assert.equal(reservationChange({action:'edited', issue:{user:{login:'vadorus'},body:'Note: Réservation-état: En cours'}}), false);
assert.equal(reservationChange({action:'edited', issue:{user:{login:'stranger'}, body:'Réservation-état: En cours'}}), false);
"""
        result = subprocess.run(["node", "-"], input=functions + "\n" + assertions,
                                text=True, capture_output=True, timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_issue_event_invalidation_is_separate_and_privileged(self):
        source = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8")
        self.assertIn("types: [opened, edited, deleted, transferred, closed, reopened]", source)
        self.assertIn("if: github.event_name == 'issues'", source)
        self.assertIn("if: github.event_name == 'pull_request_target'", source)
        self.assertIn("run.app?.id === 15368", source)
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

    def test_untrusted_issue_has_no_reservation_authority(self):
        unknown = issue(4, zones="scripts/")
        unknown["user"]["login"] = "stranger"
        self.assertEqual(preflight.conflicting_reservations(1, ["scripts/"], [unknown]), [])

    def test_tracking_phrase_not_a_reservation(self):
        tracking = {"number": 6, "state": "open", "user": {"login": "vadorus"},
                    "body": "Ce ticket cite Réservation-état sans créer de réservation."}
        self.assertIsNone(preflight.reservation_record(tracking))
        self.assertEqual(preflight.conflicting_reservations(1, ["scripts/"], [tracking]), [])

    @staticmethod
    def shared_cases():
        # Exactly the same bodies are submitted to the Python and JS parsers.
        return [
            ("Réservation-état: Réservée\\nZones: scripts/", True),
            ("Réservation-état : Réservée\\nZones: scripts/", False),
            ("**Réservation-état:** Réservée\\nZones: scripts/", False),
            ("Réservation-état: réservée\\nZones: scripts/", False),
            ("Réservation-état: Réservée\\nRéservation-état: En cours\\nZones: scripts/", False),
            ("  Réservation-état: Réservée\\nZones: scripts/", False),
            ("Réservation-état: Réservée\\nZones: `scripts/`", False),
            ("Réservation-état: Réservée\\nZones: scripts/ (tout le dossier)", False),
            ("Réservation-état: Réservée\\nZones: scripts/;docs/", False),
            ("Ceci mentionne Réservation-état dans une phrase.", None),
            ("Réservation-état: Terminée", None),
            ("Réservation-état: En revue\\nZones: docs/État.md", True),
        ]

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_shared_reservation_cases_python_and_javascript(self):
        import json
        cases = [(body.replace("\\n", "\n"), expected) for body, expected in self.shared_cases()]
        observed_python = []
        for body, expected in cases:
            candidate = {"number": 7, "body": body}
            try:
                outcome = preflight.reservation_record(candidate)
                actual = None if outcome is None else True
            except ValueError as exc:
                self.assertEqual(str(exc), "Réservation illisible : issue #7")
                actual = False
            observed_python.append(actual)
            self.assertEqual(actual, expected, body)
        script = self.workflow_script()
        source = script[script.index("function field(body, name) {"):
                        script.index("function issueSignature(items) {")]
        node = """
const values = JSON.parse(process.argv[2]);
const results = values.map(body => {
  try {
    const item = reservationState({number:7, body});
    return item ? true : null;
  } catch (error) {
    if (error.message !== 'Réservation illisible : issue #7') throw error;
    return false;
  }
});
console.log(JSON.stringify(results));
"""
        completed = subprocess.run(["node", "-", json.dumps([body for body, _ in cases], ensure_ascii=False)],
                                   input=source + "\n" + node, text=True, capture_output=True, timeout=20)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout), observed_python)

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_unicode_casefold_parity(self):
        script = self.workflow_script()
        source = script[script.index("function field(body, name) {"):
                        script.index("function issueSignature(items) {")]
        cases = ["STRASSE", "Straße", "DОCS/État.md", "DОCS/e\\u0301tat.md", "ﬃ", "FFI", "ς", "σ"]
        import json
        assertions = "console.log(JSON.stringify(" + json.dumps(cases, ensure_ascii=False) + ".map(fold)));"
        completed = subprocess.run(["node", "-"], input=source + "\n" + assertions,
                                   text=True, capture_output=True, timeout=15)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout),
                         [__import__("unicodedata").normalize("NFC", p).casefold() for p in cases])

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_three_successive_runs_on_same_sha_each_create_own_gate(self):
        """Exercise the workflow's actual creation block, not a textual assertion."""
        script = self.workflow_script()
        creation = script[script.index("const externalId = 'coordination-pr-'"):
                          script.index("await (async () => {", script.index("const externalId = 'coordination-pr-'"))]
        node = """
const assert = require('node:assert/strict');
const checks = [];
const github = {rest: {checks: {
  create: async request => {
    const data = {...request, id: checks.length + 101};
    checks.push(data);
    return {data};
  },
  update: async request => {
    const run = checks.find(item => item.id === request.check_run_id);
    assert(run);
    run.status = request.status;
    run.conclusion = request.conclusion;
  }
}}};
async function run(runId, attempt, verdict) {
  const context = {runId};
  process.env.GITHUB_RUN_ATTEMPT = String(attempt);
  const owner = 'vadorus', repo = 'Jade-genesis', number = 88;
  const expectedHead = 'c'.repeat(40);
  let check;
""" + creation + """
  await github.rest.checks.update({
    check_run_id: check.id, status: 'completed', conclusion: verdict
  });
}
(async () => {
  await run(5001, 1, 'failure');
  await run(5002, 1, 'failure');
  await run(5003, 1, 'success');
  assert.equal(checks.length, 3);
  assert.equal(new Set(checks.map(c => c.id)).size, 3);
  assert.equal(new Set(checks.map(c => c.external_id)).size, 3);
  assert.deepEqual(checks.map(c => c.conclusion), ['failure', 'failure', 'success']);
  assert(checks[2].external_id.includes('-run-5003-attempt-1'));
  assert(checks.every(c => c.head_sha === 'c'.repeat(40)));
  assert(checks.every(c => c.name === 'multi-agent-gate'));
})().catch(error => {console.error(error);process.exitCode = 1;});
"""
        result = subprocess.run(["node", "-"], input=node, text=True, capture_output=True,
                                timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(shutil.which("node"), "Node.js indisponible")
    def test_invalidator_touches_only_latest_check_per_pr(self):
        """Run the exact issue invalidation loop with synthetic GitHub check runs."""
        script = self.workflow_scripts()[1]
        start = script.index("for (const pr of pullRequests) {")
        end = script.index("core.info('Validations précédentes invalidées : '", start)
        invalidation = script[start:end]
        node = """
const assert = require('node:assert/strict');
const sha = 'c'.repeat(40);
const own = 'coordination-pr-88-head-' + sha;
const make = (id, external_id, conclusion='success', appId=15368) =>
  ({id, external_id, conclusion, status:'completed', app:{id:appId}});
const runs = [
  make(101, own),
  make(102, own + '-run-5002-attempt-1'),
  make(103, own + '-run-5003-attempt-1'),
  make(104, 'coordination-pr-89-head-' + sha + '-run-5004-attempt-1'),
  make(105, own + '-run-5005-attempt-1', 'success', 999)
];
const changed = [];
const github = {
  paginate: async () => runs,
  rest: {checks: {
    listForRef: () => {},
    update: async input => {
      changed.push(input.check_run_id);
      const run = runs.find(c => c.id === input.check_run_id);
      assert(run);
      run.status = input.status;
      run.conclusion = input.conclusion;
    }
  }}
};
async function invalidate() {
  const owner='vadorus', repo='Jade-genesis', issueNumber=90;
  const pullRequests = [{number:88, head:{sha}}];
  let invalidated = 0;
""" + invalidation + """
  return invalidated;
}
(async () => {
  assert.equal(await invalidate(), 1);
  assert.deepEqual(changed, [103]);
  assert.deepEqual(runs.map(c=>c.conclusion), [
    'success','success','failure','success','success'
  ]);
  assert.equal(await invalidate(), 0);
  assert.deepEqual(changed, [103]);
})().catch(error => {console.error(error);process.exitCode = 1;});
"""
        result = subprocess.run(["node", "-"], input=node, text=True, capture_output=True,
                                timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_workflow_rechecks_reservation_snapshot(self):
        source = (REPO / ".github" / "workflows" / "ai-coordination.yml").read_text(encoding="utf-8")
        self.assertIn("issueSignature(reservationsBefore) !== issueSignature(reservationsAfter)", source)


if __name__ == "__main__":
    unittest.main()
