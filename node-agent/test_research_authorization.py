from concurrent.futures import ThreadPoolExecutor
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from research_authorization import (
    AuthorizationRejected, ResearchAuthorizationStore, plan_hash, digest,
    official_collection_plan,
)


class AuthorizationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "audit.json"
        self.now = 1_000_000
        self.store = ResearchAuthorizationStore(self.path, clock=lambda: self.now)
        self.plan = official_collection_plan("Importer FBX Blender dans Roblox")

    def grant(self):
        return self.store.grant(self.plan, reviewer="Synthetic test reviewer",
                                note="Synthetic test permission only", confirmed=True)["authorization_id"]

    def test_no_grant_no_execution(self):
        with self.assertRaises(AuthorizationRejected):
            self.store.execute(self.plan, "invented", lambda: self.fail("Runner invoked"))
        self.assertEqual(self.store.events(), [])

    def test_missing_confirmation_or_reviewer_or_reason(self):
        for changes in ({"confirmed":False}, {"confirmed":1}, {"reviewer":""},
                        {"note":""}, {"ttl_ms":900001}):
            args = {"reviewer":"Test", "note":"Synthetic test", "confirmed":True, **changes}
            with self.subTest(changes=changes), self.assertRaises(AuthorizationRejected):
                self.store.grant(self.plan, **args)
        self.assertFalse(self.path.exists())

    def test_one_use_survives_reopen_and_is_consumed_before_runner(self):
        identifier = self.grant()
        def run():
            reopened = ResearchAuthorizationStore(self.path, clock=lambda:self.now)
            self.assertEqual(reopened.events()[-1]["kind"], "STARTED")
            with self.assertRaises(AuthorizationRejected):
                reopened.execute(self.plan, identifier, lambda:self.fail("Reentrant run"))
            return {"synthetic":True}
        self.assertEqual(self.store.execute(self.plan, identifier, run), {"synthetic":True})
        self.assertEqual([e["kind"] for e in self.store.events()], ["GRANTED","STARTED","COMPLETED"])
        with self.assertRaises(AuthorizationRejected):
            ResearchAuthorizationStore(self.path, clock=lambda:self.now).execute(
                self.plan, identifier, lambda:self.fail("Replayed run"))

    def test_changed_plan_requires_new_consent(self):
        identifier = self.grant()
        changed = copy.deepcopy(self.plan)
        changed["urls"].pop()
        with self.assertRaises(AuthorizationRejected):
            self.store.execute(changed, identifier, lambda:self.fail("Changed run"))
        self.assertEqual(self.store.events()[-1]["kind"], "GRANTED")

    def test_expired_and_backwards_clock_refused(self):
        identifier = self.grant()
        for now in (self.now-1, self.now+900_000):
            self.now=now
            with self.assertRaises(AuthorizationRejected):
                self.store.execute(self.plan, identifier, lambda:self.fail("Expired run"))

    def test_revoke_prevents_run_after_restart(self):
        identifier = self.grant()
        self.store.revoke(identifier)
        with self.assertRaises(AuthorizationRejected):
            ResearchAuthorizationStore(self.path, clock=lambda:self.now).execute(
                self.plan, identifier, lambda:self.fail("Revoked run"))

    def test_exception_consumes_permission_and_records_failure(self):
        identifier = self.grant()
        def failing():
            raise RuntimeError("Synthetic failure")
        with self.assertRaises(RuntimeError):
            self.store.execute(self.plan, identifier, failing)
        self.assertEqual(self.store.events()[-1]["kind"], "FAILED")
        with self.assertRaises(AuthorizationRejected):
            self.store.execute(self.plan, identifier, lambda:self.fail("Retry"))

    def test_atomic_write_failure_prevents_runner(self):
        identifier = self.grant()
        with patch("research_authorization.atomic_write_text", side_effect=OSError("Disk failure")):
            with self.assertRaises(OSError):
                self.store.execute(self.plan, identifier, lambda:self.fail("Unaudited run"))
        self.assertEqual(self.store.events()[-1]["kind"], "GRANTED")

    def test_changed_journal_fails_closed(self):
        identifier = self.grant()
        root=json.loads(self.path.read_text(encoding="utf-8"))
        root["events"][0]["reviewer"]="tampered"
        self.path.write_text(json.dumps(root), encoding="utf-8")
        with self.assertRaisesRegex(AuthorizationRejected, "integrity"):
            self.store.execute(self.plan, identifier, lambda:self.fail("Corrupted run"))

    def test_invalid_or_truncated_store_cannot_reset_authorizations(self):
        for content in ("{", "[]", '{"schema":"unknown"}', " " * 2_000_001):
            self.path.write_text(content, encoding="utf-8")
            with self.subTest(content=content[:20]), self.assertRaises(AuthorizationRejected):
                self.grant()

    def test_invalid_transition_rejected_even_if_hash_is_recomputed(self):
        identifier=self.grant()
        root=json.loads(self.path.read_text(encoding="utf-8"))
        event=root["events"][0]
        event["kind"]="COMPLETED"
        event["sha256"]=digest({k:v for k,v in event.items() if k!="sha256"})
        self.path.write_text(json.dumps(root), encoding="utf-8")
        with self.assertRaises(AuthorizationRejected):
            self.store.execute(self.plan, identifier, lambda:self.fail("Forged transition"))

    def test_two_instances_race_only_one_runner(self):
        identifier=self.grant()
        def attempt(_):
            other=ResearchAuthorizationStore(self.path, clock=lambda:self.now)
            try:
                return other.execute(self.plan, identifier, lambda:{"ran":True})
            except AuthorizationRejected:
                return "refused"
        with ThreadPoolExecutor(2) as pool:
            values=list(pool.map(attempt,range(2)))
        self.assertEqual(values.count({"ran":True}),1)
        self.assertEqual(values.count("refused"),1)

    def test_inspection_never_grants_execution_or_verifies_knowledge(self):
        event=self.store.record_inspection(snapshot_sha256="a"*64,source_sha256="b"*64,
                                          reviewer="Test",note="Synthetic inspection",stance="SUPPORTS")
        self.assertFalse(event["knowledge_verified"])
        self.assertFalse(event["automatic_promotion"])
        with self.assertRaises(AuthorizationRejected):
            self.store.execute(self.plan,"",lambda:self.fail("Inspection grants permission"))

    def test_unknown_scope_action_and_oversized_plan_refused(self):
        for changes in ({"action_id":"EXEC_SHELL"},{"scope":"VPS"},
                        {"automatic_promotion":True},{"payload":"x"*16001}):
            with self.subTest(changes=list(changes)), self.assertRaises(AuthorizationRejected):
                plan_hash({**self.plan,**changes})
