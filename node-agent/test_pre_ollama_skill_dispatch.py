from __future__ import annotations

import json
import subprocess
import sys
import textwrap
import unittest
from pathlib import Path


class PreOllamaSkillDispatchTest(unittest.TestCase):
    def test_verified_skill_short_circuits_ollama_and_missing_route_falls_back(self) -> None:
        node_dir = Path(__file__).resolve().parent
        script = textwrap.dedent(
            r'''
            import json
            import sys
            sys.path.insert(0, sys.argv[1])
            import jade_node_agent as agent

            class FakeRegistry:
                def __init__(self, available):
                    self.available = available
                def execute_for_family(self, identity_id, family, input_value):
                    if not self.available:
                        raise LookupError("skill_registry_no_active_skill_for_family")
                    return {
                        "selected_skill_id": "learned-normalize",
                        "selected_skill_version": 1,
                        "selected_spec_sha256": "a" * 64,
                        "verified_learned_skill": True,
                        "execution": {"result": {"text": str(input_value["text"]).strip().lower()}},
                    }

            agent._OLLAMA_COUNTERS["model_list_calls"] = 0
            agent._OLLAMA_COUNTERS["chat_calls"] = 0
            agent.open_archive_skill_registry = lambda: FakeRegistry(True)

            payload = json.dumps({
                "identity": {"jade_id": "jade-test"},
                "operation": "answer",
                "task_family": "normalize_label_v1",
                "skill_input": {"text": " JADE "},
                "user_input": "normalize",
            })
            output, _ = agent._profiled_brain_chat(payload, {})
            skill_result = json.loads(output)
            assert skill_result["backend"] == "verified_skill_registry"
            assert json.loads(skill_result["text"]) == {"text": "jade"}
            assert skill_result["ollama_calls_delta"] == 0
            assert agent.ollama_call_counters()["total_calls"] == 0

            def fake_models(*args, **kwargs):
                return [{"name": "fake-code:7b"}]
            def fake_json(url, *args, **kwargs):
                return {"message": {"content": "fallback"}}
            agent._original_ollama_models = fake_models
            agent._original_json_request = fake_json
            agent.open_archive_skill_registry = lambda: FakeRegistry(False)

            def fake_profiled(payload, config, core):
                core.ollama_models(config, timeout=0.1)
                core._json_request(
                    "http://127.0.0.1:11434/api/chat",
                    method="POST",
                    payload={},
                    timeout=0.1,
                )
                return json.dumps({"text": "fallback", "backend": "ollama"}), 1
            agent.run_brain_chat_profiled = fake_profiled

            before = agent.ollama_call_counters()["total_calls"]
            fallback_output, _ = agent._profiled_brain_chat(payload, {})
            after = agent.ollama_call_counters()["total_calls"]
            assert json.loads(fallback_output)["backend"] == "ollama"
            assert after - before == 2, (before, after)
            '''
        )
        completed = subprocess.run(
            [sys.executable, "-c", script, str(node_dir)],
            capture_output=True,
            text=True,
            timeout=20,
        )
        if completed.returncode != 0:
            self.fail(
                "isolated dispatch proof failed:\n"
                + completed.stdout
                + "\n"
                + completed.stderr
            )


if __name__ == "__main__":
    unittest.main()
