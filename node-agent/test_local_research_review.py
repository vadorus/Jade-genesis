import copy
import http.client
import json
from pathlib import Path
import tempfile
import subprocess
from types import SimpleNamespace
import threading
import unittest
from unittest.mock import patch

from goal_evidence_reasoner import RejectedEvidence
from local_research_review import LocalResearchReview, ReviewServer
from research_authorization import AuthorizationRejected
from test_goal_evidence_reasoner import research, probe_payload


class LocalReviewTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        (self.root/"node-agent").mkdir()
        self.capture=self.root/"capture_local"
        self.capture.mkdir()
        self.source=self.capture/"research.json"
        self.source.write_text(json.dumps(research()),encoding="utf-8")
        run=self.capture/"probe"
        run.mkdir()
        fbx=b"Kaydara FBX Binary  "+b"\0"*150
        (run/"synthetic_canopy_roundtrip.fbx").write_bytes(fbx)
        (run/"synthetic_canopy_roundtrip_report.json").write_text(json.dumps(probe_payload(fbx)),encoding="utf-8")
        self.service=LocalResearchReview(self.root,research="research.json",runs=("probe",))

    def body(self,action_id="OFFLINE_ARTIFACT_RECHECK"):
        state=self.service.state()
        item=next(i for i in state["plans"] if i["plan"]["action_id"]==action_id)
        return {"action_id":action_id,"snapshot_sha256":state["snapshot_sha256"],
                "plan_sha256":item["plan_sha256"],"reviewer":"Synthetic test reviewer",
                "note":"Synthetic test permission only","confirmed":True}

    def test_state_shows_validated_passages_and_bounds_of_proof(self):
        state=self.service.state()
        self.assertEqual(state["sources"][0]["excerpts"][0]["text"],research()["sources"][0]["excerpts"][0]["excerpt"])
        self.assertFalse(state["knowledge_verified"])
        self.assertEqual(len(state["plans"]),2)
        self.assertEqual(state["events"],[])

    def test_mobile_export_is_current_bounded_offline_and_does_not_transfer_grants(self):
        import hashlib
        body = self.body()
        self.service.mutate("authorize", body)
        before = self.service.store.events()
        with patch("goal_source_research.investigate_goal", side_effect=AssertionError("Network called")), \
                patch("local_research_review.subprocess.run", side_effect=AssertionError("Process called")):
            result = self.service.mutate("export-mobile", {"snapshot_sha256": body["snapshot_sha256"]})
        raw = result["dossier_json"].encode("utf-8")
        bundle = json.loads(raw)
        self.assertEqual(result["dossier_sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(result["bytes"], len(raw))
        self.assertLessEqual(len(raw), 300_000)
        self.assertFalse(result["permission_exported"])
        self.assertFalse(bundle["action_authorized"])
        self.assertNotIn("authorization_id", result["dossier_json"])
        self.assertEqual(bundle["review_packet"], self.service.dossier()["packet"])
        self.assertEqual(len(bundle["local_artifacts"]), 1)
        self.assertEqual(self.service.store.events(), before)
        self.assertFalse((self.capture / "review_runs").exists())

    def test_mobile_export_rejects_stale_display_and_concurrent_artifact_change(self):
        body = self.body()
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("export-mobile", {"snapshot_sha256": "0" * 64})
        from night_learning_lab import NightLearningLab
        original = NightLearningLab.export_official_goal_review
        def changing(lab, *args):
            bundle = original(lab, *args)
            value = research(); value["goal"] = "Un nouvel objectif FBX Blender Roblox"
            self.source.write_text(json.dumps(value), encoding="utf-8")
            return bundle
        with patch.object(NightLearningLab, "export_official_goal_review", changing), self.assertRaises(AuthorizationRejected):
            self.service.mutate("export-mobile", {"snapshot_sha256": body["snapshot_sha256"]})

    def test_mobile_export_blocks_damaged_journal(self):
        body = self.body()
        self.service.store.path.write_text("{}", encoding="utf-8")
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("export-mobile", {"snapshot_sha256": body["snapshot_sha256"]})

    def test_authorize_only_never_runs_and_revoke_blocks(self):
        body=self.body("OFFICIAL_SOURCE_COLLECTION")
        with patch("goal_source_research.investigate_goal",side_effect=AssertionError("Network called")):
            grant=self.service.mutate("authorize",body)
            self.assertFalse((self.capture/"review_runs").exists())
            self.service.mutate("revoke",{"authorization_id":grant["authorization_id"]})
            with self.assertRaises(AuthorizationRejected):
                self.service.mutate("execute",{**body,"authorization_id":grant["authorization_id"]})

    def test_offline_recheck_writes_fresh_packet_and_is_one_use(self):
        body=self.body()
        grant=self.service.mutate("authorize",body)
        request={**body,"authorization_id":grant["authorization_id"]}
        result=self.service.mutate("execute",request)
        out=Path(result["output_directory"])
        self.assertTrue(out.is_relative_to(self.capture/"review_runs"))
        self.assertTrue((out/"result.json").is_file())
        self.assertFalse(result["result"]["automatic_promotion"])
        viewed=self.service.result(grant["authorization_id"])
        self.assertEqual(viewed["result"],result["result"])
        self.assertFalse(viewed["knowledge_verified"])
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("execute",request)

    def test_result_tampering_and_unknown_result_refused(self):
        body=self.body()
        grant=self.service.mutate("authorize",body)
        result=self.service.mutate("execute",{**body,"authorization_id":grant["authorization_id"]})
        (Path(result["output_directory"])/"result.json").write_text('{"verified":true}',encoding="utf-8")
        with self.assertRaises(AuthorizationRejected):
            self.service.result(grant["authorization_id"])
        with self.assertRaises(AuthorizationRejected):
            self.service.result("../../research.json")

    def test_document_collection_is_authorized_and_results_stay_unassessed(self):
        from goal_source_research import investigate_goal
        from test_goal_source_research import FakeHttp
        body=self.body("OFFICIAL_SOURCE_COLLECTION")
        grant=self.service.mutate("authorize",body)
        with patch("goal_source_research.investigate_goal", side_effect=lambda goal:investigate_goal(
                goal,opener=lambda req,timeout:FakeHttp(req))) as collect:
            result=self.service.mutate("execute",{**body,"authorization_id":grant["authorization_id"]})
        self.assertEqual(collect.call_count,1)
        self.assertEqual(result["evaluation_status"],"UNASSESSED_DOCUMENT_CAPTURE")
        viewed=self.service.result(grant["authorization_id"])
        self.assertFalse(viewed["result"]["automatic_verification"])
        self.assertEqual(len(viewed["result"]["sources"]),3)

    def test_changed_evidence_blocks_grant_and_execution(self):
        body=self.body()
        grant=self.service.mutate("authorize",body)
        changed=research()
        changed["goal"]="Exporter un FBX Blender vers Roblox"
        self.source.write_text(json.dumps(changed),encoding="utf-8")
        for action in ("authorize","execute"):
            with self.subTest(action=action),self.assertRaises(AuthorizationRejected):
                self.service.mutate(action,{**body,"authorization_id":grant["authorization_id"]})

    def test_unapproved_or_fake_roblox_runner_refused(self):
        body=self.body()
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("execute",{**body,"authorization_id":"fake"})
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("authorize",{**body,"action_id":"ROBLOX_STUDIO_IMPORT_ISOLATED_COPY"})

    def test_forged_source_bytes_and_path_escape_refused(self):
        changed=research()
        changed["sources"][0]["excerpts"][0]["excerpt"]+=" injected"
        self.source.write_text(json.dumps(changed),encoding="utf-8")
        with self.assertRaises(RejectedEvidence):
            self.service.state()
        with self.assertRaises(ValueError):
            LocalResearchReview(self.root,research="../outside.json",runs=())
        with self.assertRaises(ValueError):
            self.service._inside(self.root.parent/"outside.json")

    def test_human_inspection_is_separate_from_permission(self):
        body=self.body()
        source=self.service.dossier()["sources"][0]["sha256"]
        self.service.mutate("inspect",{**body,"source_sha256":source,"stance":"CONTRADICTS"})
        state=self.service.state()
        self.assertEqual(state["events"][-1]["stance"],"CONTRADICTS")
        self.assertFalse(state["knowledge_verified"])
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("execute",{**body,"authorization_id":""})

    def configure_fake_blender(self):
        # Never launch Blender in automated tests; simulate only the process boundary.
        executable=self.root/"blender.exe"
        executable.write_bytes(b"synthetic fake executable never launched")
        script=self.root/"node-agent"/"blender_fbx_roundtrip_probe.py"
        script.write_bytes(b"# synthetic test fixture, never executed\n")
        self.service.blender=executable
        return executable,script

    def test_blender_gate_builds_fixed_command_and_checks_synthetic_result(self):
        self.configure_fake_blender()
        body=self.body("BLENDER_SYNTHETIC_FBX_ROUNDTRIP")
        grant=self.service.mutate("authorize",body)
        def fake_process(args,**kwargs):
            self.assertFalse(kwargs["shell"])
            self.assertIn("--factory-startup",args)
            self.assertIn("--disable-autoexec",args)
            self.assertEqual(kwargs["timeout"],60)
            destination=Path(args[-1])
            self.assertTrue(destination.is_relative_to(self.capture/"review_runs"))
            fbx=b"Kaydara FBX Binary  "+b"\0"*150
            (destination/"synthetic_canopy_roundtrip.fbx").write_bytes(fbx)
            (destination/"synthetic_canopy_roundtrip_report.json").write_text(json.dumps(probe_payload(fbx)),encoding="utf-8")
            return SimpleNamespace(returncode=0)
        with patch("local_research_review.subprocess.run",side_effect=fake_process) as process:
            result=self.service.mutate("execute",{**body,"authorization_id":grant["authorization_id"]})
        self.assertEqual(process.call_count,1)
        self.assertFalse(result["result"]["experiment"]["trusted_external_attestation"])
        terminal=self.service.store.events()[-1]
        self.assertEqual(terminal["result_summary"]["output_directory"],result["output_directory"])

    def test_changed_executable_or_script_refused_before_process(self):
        executable,script=self.configure_fake_blender()
        body=self.body("BLENDER_SYNTHETIC_FBX_ROUNDTRIP")
        grant=self.service.mutate("authorize",body)
        for path in (executable,script):
            original=path.read_bytes()
            path.write_bytes(original+b"changed")
            with patch("local_research_review.subprocess.run",side_effect=AssertionError("Process invoked")):
                with self.assertRaises(AuthorizationRejected):
                    self.service.mutate("execute",{**body,"authorization_id":grant["authorization_id"]})
            path.write_bytes(original)

    def test_blender_timeout_is_logged_and_permission_consumed(self):
        self.configure_fake_blender()
        body=self.body("BLENDER_SYNTHETIC_FBX_ROUNDTRIP")
        grant=self.service.mutate("authorize",body)
        request={**body,"authorization_id":grant["authorization_id"]}
        with patch("local_research_review.subprocess.run",side_effect=subprocess.TimeoutExpired("fake",60)):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.service.mutate("execute",request)
        self.assertEqual(self.service.store.events()[-1]["kind"],"FAILED")
        with self.assertRaises(AuthorizationRejected):
            self.service.mutate("execute",request)


class LocalHttpReviewTests(LocalReviewTests):
    def setUp(self):
        super().setUp()
        self.server=ReviewServer(self.service)
        thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(self,path,*,method="GET",body=None,headers=None):
        conn=http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=5)
        self.addCleanup(conn.close)
        conn.request(method,path,body,headers or {})
        response=conn.getresponse()
        return response.status,response.getheaders(),response.read()

    def test_loopback_session_headers_and_no_caching(self):
        code,headers,raw=self.request("/")
        self.assertEqual(code,200)
        self.assertIn(b'lang="fr"',raw)
        self.assertIn(self.server.csrf.encode(),raw)
        self.assertEqual(dict(headers)["Cache-Control"],"no-store")
        self.assertIn("frame-ancestors 'none'",dict(headers)["Content-Security-Policy"])
        self.assertEqual(self.request("/api/state")[0],403)
        self.assertEqual(self.request("/api/state",headers={"X-Jade-Review":self.server.csrf})[0],200)

    def test_http_export_needs_local_session_origin_and_current_snapshot(self):
        body = json.dumps({"snapshot_sha256": self.service.state()["snapshot_sha256"]})
        headers = {"Content-Type": "application/json", "X-Jade-Review": self.server.csrf, "Origin": self.server.origin}
        for overrides in ({"Origin": "https://foreign.invalid"}, {"X-Jade-Review": "fake"}, {"Host": "foreign.invalid"}):
            self.assertEqual(self.request("/api/export-mobile", method="POST", body=body, headers={**headers, **overrides})[0], 403)
        code, response_headers, raw = self.request("/api/export-mobile", method="POST", body=body, headers=headers)
        self.assertEqual(code, 200)
        self.assertEqual(dict(response_headers)["Cache-Control"], "no-store")
        self.assertFalse(json.loads(raw)["permission_exported"])
        self.assertEqual(self.service.store.events(), [])

    def test_foreign_origin_missing_token_and_rebound_host_cannot_authorize(self):
        good={"Content-Type":"application/json","X-Jade-Review":self.server.csrf,"Origin":self.server.origin}
        body=json.dumps(self.body())
        for overrides in ({"Origin":"https://attacker.invalid"},{"X-Jade-Review":"fake"},
                          {"Host":"attacker.invalid"},{"Origin":"null"}):
            with self.subTest(overrides=overrides):
                self.assertEqual(self.request("/api/authorize",method="POST",body=body,headers={**good,**overrides})[0],403)
        self.assertEqual(self.service.store.events(),[])

    def test_http_permission_then_execute_and_replay_refused(self):
        headers={"Content-Type":"application/json","X-Jade-Review":self.server.csrf,"Origin":self.server.origin}
        body=self.body()
        code,_,raw=self.request("/api/authorize",method="POST",body=json.dumps(body),headers=headers)
        self.assertEqual(code,200)
        body["authorization_id"]=json.loads(raw)["authorization_id"]
        self.assertEqual(self.request("/api/execute",method="POST",body=json.dumps(body),headers=headers)[0],200)
        self.assertEqual(self.request("/api/execute",method="POST",body=json.dumps(body),headers=headers)[0],409)

    def test_malformed_body_and_arbitrary_paths_rejected(self):
        headers={"Content-Type":"application/json","X-Jade-Review":self.server.csrf,"Origin":self.server.origin}
        for body in ("[]","{", "x"*4097):
            self.assertEqual(self.request("/api/authorize",method="POST",body=body,headers=headers)[0],409)
        for path in ("/../../README.md","/capture_local/research.json","/api/exec_shell"):
            self.assertEqual(self.request(path)[0],404)
