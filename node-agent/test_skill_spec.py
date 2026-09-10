from __future__ import annotations

import unittest

from skill_spec import BODY_KIND, normalize_skill_spec, skill_spec_status


class SkillSpecTest(unittest.TestCase):
    @staticmethod
    def valid_spec() -> dict:
        return {
            "schema_version": 1,
            "skill_id": "json-name-normalizer",
            "skill_version": 1,
            "task_family": "synthetic_json_transform",
            "domain": "verifiable_procedure",
            "description": "Normalize and concatenate two structured fields.",
            "input_contract": {
                "type": "object",
                "required_fields": ["first", "last"],
            },
            "output_contract": {
                "type": "object",
                "required_fields": ["value"],
            },
            "body_kind": BODY_KIND,
            "body": {
                "op": "concat",
                "args": [
                    {
                        "op": "get",
                        "args": [{"op": "literal", "args": ["first"]}],
                    },
                    {"op": "literal", "args": [" "]},
                    {
                        "op": "get",
                        "args": [{"op": "literal", "args": ["last"]}],
                    },
                ],
            },
            "dependencies": [],
            "provenance": {
                "source_kind": "EXTERNAL_TEACHER",
                "source_id": "teacher-session-1",
                "source_model": "external-model",
                "created_at": 1_000,
            },
            "evaluation_policy": {
                "verifier_kind": "exact_json_v1",
                "sealed_set_sha256": "a" * 64,
                "min_pass_rate": 0.9,
                "max_protected_failures": 0,
            },
        }

    def test_valid_skill_has_stable_payload_but_contract_does_not_enable_execution(self) -> None:
        first = normalize_skill_spec(self.valid_spec())
        second = normalize_skill_spec(self.valid_spec())
        self.assertEqual(first["body_sha256"], second["body_sha256"])
        self.assertEqual(first["spec_sha256"], second["spec_sha256"])
        self.assertEqual(64, len(first["body_sha256"]))
        self.assertEqual(64, len(first["spec_sha256"]))
        self.assertFalse(first["execution_enabled"])
        self.assertFalse(first["network_allowed"])
        self.assertFalse(first["filesystem_allowed"])
        self.assertFalse(first["shell_allowed"])
        self.assertFalse(first["arbitrary_code_allowed"])
        self.assertFalse(first["model_weight_mutation"])

    def test_unknown_or_dangerous_operation_is_rejected(self) -> None:
        spec = self.valid_spec()
        spec["body"] = {"op": "shell", "args": []}
        with self.assertRaisesRegex(ValueError, "skill_body_op_not_allowed"):
            normalize_skill_spec(spec)

    def test_arbitrary_python_body_kind_is_rejected(self) -> None:
        spec = self.valid_spec()
        spec["body_kind"] = "PYTHON"
        with self.assertRaisesRegex(ValueError, "unsupported_skill_body_kind"):
            normalize_skill_spec(spec)

    def test_invalid_sealed_hash_is_rejected(self) -> None:
        spec = self.valid_spec()
        spec["evaluation_policy"]["sealed_set_sha256"] = "not-a-real-hash"
        with self.assertRaisesRegex(ValueError, "invalid_sealed_set_sha256"):
            normalize_skill_spec(spec)

    def test_literal_cannot_smuggle_forbidden_capability_key(self) -> None:
        spec = self.valid_spec()
        spec["body"] = {
            "op": "literal",
            "args": [{"network": "https://example.invalid"}],
        }
        with self.assertRaisesRegex(ValueError, "skill_forbidden_capability"):
            normalize_skill_spec(spec)

    def test_dependencies_are_rejected_in_0_1_20(self) -> None:
        spec = self.valid_spec()
        spec["dependencies"] = ["skill-a"]
        with self.assertRaisesRegex(
            PermissionError,
            "skill_dependencies_disabled_in_0_1_20",
        ):
            normalize_skill_spec(spec)

    def test_contract_rejects_unknown_fields(self) -> None:
        spec = self.valid_spec()
        spec["input_contract"]["python_type"] = "dict"
        with self.assertRaisesRegex(ValueError, "unsupported_input_contract_field"):
            normalize_skill_spec(spec)

    def test_status_keeps_execution_boundary_explicit(self) -> None:
        status = skill_spec_status()
        self.assertEqual(BODY_KIND, status["body_kind"])
        self.assertFalse(status["execution_enabled"])
        self.assertFalse(status["interpreter_present"])
        self.assertFalse(status["generated_skill_execution"])
        self.assertFalse(status["dependencies_allowed"])
        self.assertEqual(0, status["max_dependencies"])
        self.assertFalse(status["network_allowed"])
        self.assertFalse(status["shell_allowed"])


if __name__ == "__main__":
    unittest.main()
