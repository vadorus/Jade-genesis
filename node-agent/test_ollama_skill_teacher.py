from __future__ import annotations

import unittest

from ollama_skill_teacher import OllamaSkillTeacher


class FakeCore:
    DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"

    def __init__(self, content: str):
        self.content = content
        self.models_calls = 0
        self.chat_calls = 0
        self.last_payload = None

    @staticmethod
    def normalize_ollama_url(value: str) -> str:
        return value.rstrip("/")

    @staticmethod
    def gpu_telemetry() -> dict:
        return {"gpu_vram_free_gb": 16.0}

    def ollama_models(self, config: dict, timeout: float = 1.2) -> list[dict]:
        self.models_calls += 1
        return [
            {
                "name": "qwen2.5-coder:14b",
                "size": 8 * 1024**3,
                "details": {"parameter_size": "14B"},
            }
        ]

    def _json_request(self, url: str, *, method: str, payload: dict, timeout: float) -> dict:
        self.chat_calls += 1
        self.last_payload = payload
        return {"message": {"content": self.content}}


class OllamaSkillTeacherTest(unittest.TestCase):
    @staticmethod
    def request() -> dict:
        return {
            "request_kind": "JADE_SKILL_TEACHER_REQUEST_V1",
            "goal_id": "goal-1",
            "task_family": "normalize_label_v1",
            "reason": "Normalize labels.",
            "input_contract": {"type": "object", "required_fields": ["text"]},
            "output_contract": {"type": "object", "required_fields": ["text"]},
            "body_kind": "JADE_PROCEDURE_DSL_V1",
            "allowed_ops": ["get", "literal", "lower", "object", "trim"],
            "dependencies_allowed": False,
            "visible_cases": [
                {
                    "case_id": "train-1",
                    "partition": "TRAIN",
                    "input": {"text": " JADE "},
                    "expected_output": {"text": "jade"},
                }
            ],
            "previous_attempts": [],
            "sealed_set_sha256": "a" * 64,
            "sealed_test_count": 2,
            "sealed_test_inputs_exposed": False,
            "sealed_test_answers_exposed": False,
            "seal_nonce_exposed": False,
            "required_response": {
                "skill_id": "short stable identifier",
                "description": "brief description",
                "domain": "verifiable_procedure",
                "body": {"op": "...", "args": []},
            },
        }

    def test_teacher_uses_code_model_and_returns_only_proposal(self) -> None:
        core = FakeCore(
            '{"skill_id":"normalize-label","description":"Normalize",'
            '"domain":"verifiable_procedure","body":{"op":"object","args":[]}}'
        )
        teacher = OllamaSkillTeacher({"ollama_url": "http://local"}, core)
        result = teacher(self.request())
        self.assertEqual("normalize-label", result["skill_id"])
        self.assertEqual(1, core.models_calls)
        self.assertEqual(1, core.chat_calls)
        self.assertEqual(1, teacher.call_count)
        self.assertEqual("qwen2.5-coder:14b", teacher.last_model)
        self.assertTrue(core.last_payload["format"] == "json")
        system = core.last_payload["messages"][0]["content"]
        self.assertIn("provenance", system)
        self.assertIn("SEALED_TEST", system)

    def test_teacher_rejects_forbidden_response_fields(self) -> None:
        core = FakeCore(
            '{"skill_id":"bad","description":"bad","domain":"x",'
            '"body":{"op":"literal","args":[1]},'
            '"evaluation_policy":{"min_pass_rate":0}}'
        )
        teacher = OllamaSkillTeacher({}, core)
        with self.assertRaisesRegex(ValueError, "teacher_response_forbidden_field"):
            teacher(self.request())

    def test_teacher_refuses_request_that_exposes_sealed_inputs(self) -> None:
        core = FakeCore('{}')
        teacher = OllamaSkillTeacher({}, core)
        request = self.request()
        request["sealed_test_inputs_exposed"] = True
        with self.assertRaisesRegex(
            PermissionError,
            "teacher_sealed_inputs_must_remain_hidden",
        ):
            teacher(request)
        self.assertEqual(0, core.models_calls)
        self.assertEqual(0, core.chat_calls)

    def test_markdown_fenced_json_is_tolerated_but_contract_stays_strict(self) -> None:
        core = FakeCore(
            "```json\n"
            '{"skill_id":"normalize-label","description":"Normalize",'
            '"domain":"verifiable_procedure","body":{"op":"object","args":[]}}'
            "\n```"
        )
        teacher = OllamaSkillTeacher({}, core)
        proposal = teacher(self.request())
        self.assertEqual(set(proposal), {"skill_id", "description", "domain", "body"})


if __name__ == "__main__":
    unittest.main()
