import contextlib
import io
import json
import tempfile
import unittest
import threading
import time
from http.server import ThreadingHTTPServer
import urllib.request
import urllib.error
from pathlib import Path
from unittest import mock

import correct_mem as cm
import jade_node_runtime_core as core
import jade_progress


class CorrectMemTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def halt(self, **overrides):
        raw = dict(schema_version=1, state="RUNNING", reason="", source="",
                   at_ms=0, sealed_access_violations=0, consecutive_protected_failures=0)
        raw.update(overrides)
        (self.root / "jade-halt.json").write_text(json.dumps(raw))

    def test_halt_absent_running_without_writing(self):
        self.assertEqual("RUNNING", cm.halt_projection(self.root)["state"])
        self.assertEqual([], list(self.root.iterdir()))

    def test_halt_unreadable_or_invalid_fail_closed_without_journal(self):
        for raw in ("broken", "[]", '{"schema_version":2}', '{"schema_version":1,"state":"RUNNING"}'):
            path = self.root / "jade-halt.json"
            path.write_text(raw)
            before = path.read_bytes()
            self.assertEqual("HALTED", cm.halt_projection(self.root)["state"])
            self.assertEqual(before, path.read_bytes())
            self.assertEqual([path], list(self.root.iterdir()))
        with mock.patch.object(Path, "read_text", side_effect=PermissionError):
            self.assertEqual("HALTED", cm.halt_projection(self.root)["state"])

    def test_halt_valid(self):
        for state in ("RUNNING", "HALTED"):
            self.halt(state=state)
            self.assertEqual(state, cm.halt_projection(self.root)["state"])

    def context(self):
        return {"user_input": "PRIVATE_QUESTION", "budget_ms": 1000,
                "corrections": [{"source": "USER_CORRECTION", "confidence": 1.0,
                                 "content": "PRIVATE_CORRECTION 42"}]}

    def test_invalid_facts_rejected_without_private_error(self):
        for row in ({"content": "private"}, {"source": "USER_CORRECTION", "confidence": True, "content": "private"}):
            with self.assertRaisesRegex(ValueError, "^invalid_correction_context$"):
                cm.correction_prompt({"corrections": [row]})

    def test_prompts_answer_verify_revise_have_separate_quota(self):
        for operation in ("answer", "verify", "revise"):
            context = self.context() | {"operation": operation, "memories": [{"content": "ordinary"}] * 10}
            prompt = core._brain_system_prompt(context)
            self.assertIn(cm.INSTRUCTION, prompt)
            self.assertIn("PRIVATE_CORRECTION 42", prompt)
            self.assertNotIn("PRIVATE_QUESTION", prompt)
        with self.assertRaisesRegex(ValueError, "invalid_correction_context"):
            cm.correction_prompt({"corrections": self.context()["corrections"] * 6})

    def exam(self, opener, context=None):
        return cm.run_exam(context or self.context(), {"ollama_model": "synthetic"}, self.root,
                           core._brain_system_prompt, core._brain_user_prompt, opener)

    def test_exam_no_dispatch_no_store_no_stdout_and_clean_options(self):
        captured = []
        def opener(request, timeout):
            captured.append(json.loads(request.data))
            return io.BytesIO(json.dumps({"message": {"content": "42"}}).encode())
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.object(core, "execute_allowlisted_task", side_effect=AssertionError):
            result = self.exam(opener, self.context() | {"task_family": "normalize_label_v1", "tools": ["bad"], "operation": "tool_build"})
        self.assertEqual("42", result["text"])
        self.assertEqual("", output.getvalue())
        self.assertEqual([], list(self.root.iterdir()))
        self.assertNotIn("task_family", json.dumps(captured))
        self.assertNotIn("tool_build", json.dumps(captured))

    def test_halt_before_and_after_model(self):
        self.halt(state="HALTED")
        opener = mock.Mock()
        with self.assertRaisesRegex(ValueError, "halted"):
            self.exam(opener)
        opener.assert_not_called()
        self.halt()
        def changed(*args, **kwargs):
            self.halt(state="HALTED")
            return io.BytesIO(b'{"message":{"content":"42"}}')
        with self.assertRaisesRegex(ValueError, "halted"):
            self.exam(changed)

    def test_exception_does_not_expose_private_body(self):
        with self.assertRaises(ValueError) as error:
            self.exam(mock.Mock(side_effect=RuntimeError("PRIVATE_CORRECTION PRIVATE_QUESTION")))
        self.assertEqual("correction_exam_unavailable", str(error.exception))

    def test_budget_invalid_and_response_after_deadline_rejected(self):
        for budget in (True, 0, 90001):
            with self.assertRaisesRegex(ValueError, "budget"):
                self.exam(mock.Mock(), self.context() | {"budget_ms": budget})
        with mock.patch.object(cm.time, "monotonic", side_effect=[0, 2]):
            with self.assertRaisesRegex(ValueError, "budget"):
                self.exam(mock.Mock(return_value=io.BytesIO(b'{"message":{"content":"42"}}')))

    def test_absolute_deadline_even_if_upstream_ignores_socket_timeout(self):
        release = threading.Event()
        entered = threading.Event()
        def blocked(*args, **kwargs):
            entered.set()
            release.wait(1)
            return io.BytesIO(b'{"message":{"content":"42"}}')
        started = time.monotonic()
        try:
            with self.assertRaisesRegex(ValueError, "budget"):
                self.exam(blocked, self.context() | {"budget_ms": 100})
            self.assertTrue(entered.is_set())
            self.assertLess(time.monotonic() - started, .8)
            with self.assertRaisesRegex(ValueError, "busy"):
                self.exam(mock.Mock())
        finally:
            release.set()
            with cm._EXAM_MODEL_SLOT:
                pass

    def test_metrics_latest_snapshot_only_safe_and_read_only(self):
        payload = dict(schema_version=1, observed_at=1000, active=4, pending=1,
                       night="2026-10-18", attempts=3, replayed=2, evaluable=1,
                       repeated=1, unavailable=1, interrupted=0, exam_updated_at=900,
                       private="PRIVATE_CORRECTION")
        events = {"first": {"kind": "correction_metrics", "payload": json.dumps(payload)},
                  "duplicate": {"kind": "correction_metrics", "payload": json.dumps(payload)}}
        path = self.root / "genesis-state.json"
        path.write_text(json.dumps({"schema_version": 1, "entities": events}))
        before = path.read_bytes()
        result = cm.correction_metrics(self.root, 1001)
        self.assertEqual(4, result["active"])
        self.assertIsNone(result["repeat_rate"])
        self.assertEqual(1, result["repeated"])
        self.assertEqual(2, result["replayed"])
        self.assertTrue(result["partial"])
        report = jade_progress.generate_report(self.root, now_ms=1001)
        self.assertEqual(result, report["corrections"])
        self.assertEqual("latest_snapshot_only_lexical_indicator_with_correction_in_context", result["coverage"])
        self.assertIn("correction fournie dans le contexte", jade_progress.format_text(report))
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(before, path.read_bytes())
        self.assertEqual([path], list(self.root.iterdir()))

    def test_no_replays_unknown_and_bad_snapshot_not_zero(self):
        self.assertIsNone(cm.correction_metrics(self.root, 1001))
        path = self.root / "genesis-state.json"
        path.write_text('{"schema_version":1,"entities":{"x":{"kind":"correction_metrics","payload":"{}"}}}')
        self.assertIsNone(cm.correction_metrics(self.root, 1001))

    def test_http_private_endpoint_auth_and_exception_redaction(self):
        store = mock.Mock()
        handler = core.make_handler({"token": "synthetic-token", "node_id": "synthetic"}, store)
        class Quiet(handler):
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        def request(path, data=None, authorized=True):
            return urllib.request.Request(base + path,
                data=json.dumps(data).encode() if data is not None else None,
                headers={"X-Jade-Token": "synthetic-token" if authorized else "wrong"})
        try:
            with mock.patch.object(core, "CONFIG_DIR", self.root):
                with urllib.request.urlopen(request("/correct-mem/status"), timeout=2) as response:
                    self.assertEqual("RUNNING", json.load(response)["state"])
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request("/correct-mem/status", authorized=False), timeout=2)
                self.assertEqual(401, error.exception.code)
                with mock.patch.object(core, "run_exam", side_effect=RuntimeError("PRIVATE_CORRECTION")):
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request("/correct-mem/exam", self.context()), timeout=2)
                    self.assertNotIn("PRIVATE", error.exception.read().decode())
                with mock.patch.object(core, "execute_allowlisted_task", side_effect=RuntimeError("PRIVATE_CORRECTION")), mock.patch.object(core, "log_event") as log:
                    data = {"task_id": "synthetic", "task_kind": "brain_chat", "payload": json.dumps(self.context()), "iterations": 1}
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request("/task", data), timeout=2)
                    self.assertNotIn("PRIVATE", error.exception.read().decode())
                    self.assertNotIn("PRIVATE", str(log.call_args_list))
            store.submit.assert_not_called()
            self.assertEqual([], list(self.root.iterdir()))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


if __name__ == "__main__":
    unittest.main()
