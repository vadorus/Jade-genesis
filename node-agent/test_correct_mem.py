import unittest
import correct_mem as cm
import jade_node_runtime_core as core
import json
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
from unittest import mock

class CorrectMemTest(unittest.TestCase):
    def context(self):
        return {"user_input": "PRIVATE_QUESTION", "budget_ms": 1000,
                "corrections": [{"source": "USER_CORRECTION", "confidence": 1.0,
                                 "content": "PRIVATE_CORRECTION 42"}]}

    def test_prompts_answer_verify_revise_have_separate_quota(self):
        for operation in ("answer", "verify", "revise"):
            context = self.context() | {"operation": operation, "memories": [{"content": "ordinary"}] * 10}
            prompt = core._brain_system_prompt(context)
            self.assertIn(cm.INSTRUCTION, prompt)
            self.assertIn("PRIVATE_CORRECTION 42", prompt)
            self.assertNotIn("PRIVATE_QUESTION", prompt)
        with self.assertRaisesRegex(ValueError, "invalid_correction_context"):
            cm.correction_prompt({"corrections": self.context()["corrections"] * 6})

    def test_invalid_facts_rejected_without_private_error(self):
        for row in ({"content": "private"}, {"source": "USER_CORRECTION", "confidence": True, "content": "private"}):
            with self.assertRaisesRegex(ValueError, "^invalid_correction_context$"):
                cm.correction_prompt({"corrections": [row]})

    def test_private_conversation_errors_redacted_and_not_queued(self):
        store = mock.Mock()
        handler = core.make_handler({"token": "synthetic-token", "node_id": "synthetic"}, store)
        class Quiet(handler):
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        data = {"task_id": "synthetic", "task_kind": "brain_chat", "payload": json.dumps(self.context()), "iterations": 1}
        request = urllib.request.Request("http://127.0.0.1:" + str(server.server_port) + "/task",
            data=json.dumps(data).encode(), headers={"X-Jade-Token": "synthetic-token"})
        try:
            with mock.patch.object(core, "execute_allowlisted_task", side_effect=RuntimeError("PRIVATE_CORRECTION")), mock.patch.object(core, "log_event") as log:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(request, timeout=2)
                self.assertNotIn("PRIVATE", error.exception.read().decode())
                self.assertNotIn("PRIVATE", str(log.call_args_list))
            store.submit.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
