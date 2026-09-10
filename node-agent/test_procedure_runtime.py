from __future__ import annotations

import unittest

from procedure_runtime import (
    ProcedureRuntimeError,
    execute_skill,
    procedure_runtime_status,
)
from skill_spec import BODY_KIND


class ProcedureRuntimeTest(unittest.TestCase):
    @staticmethod
    def skill(
        *,
        source_kind: str = "DEVELOPER",
        body: dict | None = None,
    ) -> dict:
        return {
            "schema_version": 1,
            "skill_id": "sum-fields",
            "skill_version": 1,
            "task_family": "synthetic_json_transform",
            "domain": "verifiable_procedure",
            "description": "Return a+b as a structured value.",
            "input_contract": {
                "type": "object",
                "required_fields": ["a", "b"],
            },
            "output_contract": {
                "type": "object",
                "required_fields": ["value"],
            },
            "body_kind": BODY_KIND,
            "body": body
            or {
                "op": "object",
                "args": [
                    {"op": "literal", "args": ["value"]},
                    {
                        "op": "add",
                        "args": [
                            {
                                "op": "get",
                                "args": [{"op": "literal", "args": ["a"]}],
                            },
                            {
                                "op": "get",
                                "args": [{"op": "literal", "args": ["b"]}],
                            },
                        ],
                    },
                ],
            },
            "dependencies": [],
            "provenance": {
                "source_kind": source_kind,
                "source_id": "manual-test",
                "source_model": "",
                "created_at": 1_000,
            },
            "evaluation_policy": {
                "verifier_kind": "exact_json_v1",
                "sealed_set_sha256": "a" * 64,
                "min_pass_rate": 1.0,
                "max_protected_failures": 0,
            },
        }

    def test_developer_skill_executes_deterministically(self) -> None:
        first = execute_skill(self.skill(), {"a": 7, "b": 5})
        second = execute_skill(self.skill(), {"b": 5, "a": 7})
        self.assertEqual({"value": 12}, first["result"])
        self.assertEqual(first["result"], second["result"])
        self.assertEqual(first["spec_sha256"], second["spec_sha256"])
        self.assertTrue(first["deterministic"])
        self.assertFalse(first["network_used"])
        self.assertFalse(first["filesystem_used"])
        self.assertFalse(first["shell_used"])
        self.assertFalse(first["process_used"])
        self.assertFalse(first["randomness_used"])
        self.assertFalse(first["generated_skill_execution"])

    def test_external_teacher_skill_is_not_executable_in_0_1_20(self) -> None:
        with self.assertRaisesRegex(
            PermissionError,
            "procedure_skill_source_not_executable",
        ):
            execute_skill(
                self.skill(source_kind="EXTERNAL_TEACHER"),
                {"a": 1, "b": 2},
            )

    def test_input_contract_is_enforced(self) -> None:
        with self.assertRaisesRegex(
            ProcedureRuntimeError,
            "procedure_input_contract_missing_field",
        ):
            execute_skill(self.skill(), {"a": 1})

    def test_output_contract_is_enforced(self) -> None:
        spec = self.skill(body={"op": "literal", "args": [7]})
        with self.assertRaisesRegex(
            ProcedureRuntimeError,
            "procedure_output_contract_type_mismatch",
        ):
            execute_skill(spec, {"a": 1, "b": 2})

    def test_if_short_circuits_unselected_branch(self) -> None:
        body = {
            "op": "if",
            "args": [
                {
                    "op": "equals",
                    "args": [
                        {"op": "get", "args": [{"op": "literal", "args": ["a"]}]},
                        {"op": "literal", "args": [1]},
                    ],
                },
                {
                    "op": "object",
                    "args": [
                        {"op": "literal", "args": ["value"]},
                        {"op": "literal", "args": [10]},
                    ],
                },
                {
                    "op": "divide",
                    "args": [
                        {"op": "literal", "args": [1]},
                        {"op": "literal", "args": [0]},
                    ],
                },
            ],
        }
        result = execute_skill(self.skill(body=body), {"a": 1, "b": 2})
        self.assertEqual({"value": 10}, result["result"])

    def test_division_by_zero_fails_closed(self) -> None:
        body = {
            "op": "object",
            "args": [
                {"op": "literal", "args": ["value"]},
                {
                    "op": "divide",
                    "args": [
                        {"op": "literal", "args": [1]},
                        {"op": "literal", "args": [0]},
                    ],
                },
            ],
        }
        with self.assertRaisesRegex(
            ProcedureRuntimeError,
            "procedure_division_by_zero",
        ):
            execute_skill(self.skill(body=body), {"a": 1, "b": 2})

    def test_logical_budget_is_enforced(self) -> None:
        with self.assertRaisesRegex(
            ProcedureRuntimeError,
            "procedure_step_budget_exceeded",
        ):
            execute_skill(
                self.skill(),
                {"a": 1, "b": 2},
                max_steps=2,
            )

    def test_generated_python_objects_are_rejected_as_inputs(self) -> None:
        class HostObject:
            pass

        with self.assertRaisesRegex(
            ProcedureRuntimeError,
            "procedure_unsupported_value_type",
        ):
            execute_skill(self.skill(), {"a": HostObject(), "b": 2})

    def test_string_operations_remain_bounded_json(self) -> None:
        spec = self.skill(
            body={
                "op": "object",
                "args": [
                    {"op": "literal", "args": ["value"]},
                    {
                        "op": "upper",
                        "args": [
                            {
                                "op": "trim",
                                "args": [
                                    {
                                        "op": "get",
                                        "args": [
                                            {"op": "literal", "args": ["name"]}
                                        ],
                                    }
                                ],
                            }
                        ],
                    },
                ],
            }
        )
        spec["skill_id"] = "normalize-name"
        spec["input_contract"]["required_fields"] = ["name"]
        result = execute_skill(spec, {"name": "  jade  "})
        self.assertEqual({"value": "JADE"}, result["result"])

    def test_status_does_not_overclaim_generated_execution(self) -> None:
        status = procedure_runtime_status()
        self.assertTrue(status["interpreter_present"])
        self.assertTrue(status["execution_enabled"])
        self.assertTrue(status["developer_authored_execution"])
        self.assertFalse(status["generated_skill_execution"])
        self.assertFalse(status["external_teacher_execution"])
        self.assertFalse(status["dependencies_allowed"])
        self.assertTrue(status["deterministic"])
        self.assertFalse(status["network_allowed"])
        self.assertFalse(status["filesystem_allowed"])
        self.assertFalse(status["shell_allowed"])
        self.assertFalse(status["process_allowed"])
        self.assertFalse(status["os_process_isolation"])
        self.assertFalse(status["untrusted_generated_execution"])


if __name__ == "__main__":
    unittest.main()
