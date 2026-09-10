from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from skill_registry import SkillRegistry
from skill_spec import BODY_KIND


class SkillRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="jade-skill-registry-")
        self.path = Path(self.temp.name) / "skills.json"
        self.registry = SkillRegistry(self.path)
        self.identity = "jade-test"

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def sum_skill(*, source_kind: str = "DEVELOPER", version: int = 1) -> dict:
        return {
            "schema_version": 1,
            "skill_id": "sum-fields",
            "skill_version": version,
            "task_family": "synthetic_json_sum",
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
            "body": {
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
                "source_id": "registry-test",
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

    def test_register_does_not_auto_activate(self) -> None:
        saved = self.registry.register_developer_skill(
            self.identity,
            self.sum_skill(),
            now_ms=1_100,
        )
        self.assertEqual("sum-fields", saved["skill_id"])
        self.assertIsNone(
            self.registry.selected_skill(self.identity, "synthetic_json_sum")
        )
        status = self.registry.status()
        self.assertEqual(1, status["registered_skill_count"])
        self.assertEqual(0, status["active_family_count"])
        self.assertFalse(status["activation_automatic"])

    def test_activation_requires_explicit_approval(self) -> None:
        self.registry.register_developer_skill(
            self.identity,
            self.sum_skill(),
            now_ms=1_100,
        )
        with self.assertRaisesRegex(
            PermissionError,
            "skill_registry_activation_requires_approval",
        ):
            self.registry.activate_developer_skill(
                self.identity,
                "sum-fields",
                1,
                approved=False,
                now_ms=1_200,
            )

    def test_external_teacher_skill_cannot_enter_0_1_20_registry(self) -> None:
        with self.assertRaisesRegex(
            PermissionError,
            "skill_registry_developer_source_required",
        ):
            self.registry.register_developer_skill(
                self.identity,
                self.sum_skill(source_kind="EXTERNAL_TEACHER"),
                now_ms=1_100,
            )

    def test_exact_family_selection_executes_without_llm(self) -> None:
        self.registry.register_developer_skill(
            self.identity,
            self.sum_skill(),
            now_ms=1_100,
        )
        self.registry.activate_developer_skill(
            self.identity,
            "sum-fields",
            1,
            approved=True,
            now_ms=1_200,
        )
        result = self.registry.execute_for_family(
            self.identity,
            "synthetic_json_sum",
            {"a": 8, "b": 13},
        )
        self.assertEqual("exact_task_family", result["selection_kind"])
        self.assertEqual("sum-fields", result["selected_skill_id"])
        self.assertEqual({"value": 21}, result["execution"]["result"])
        self.assertFalse(result["llm_used"])
        self.assertFalse(result["network_used"])
        self.assertFalse(result["selection_automatic_learning"])

    def test_registry_survives_restart_and_reuses_active_skill(self) -> None:
        self.registry.register_developer_skill(
            self.identity,
            self.sum_skill(),
            now_ms=1_100,
        )
        self.registry.activate_developer_skill(
            self.identity,
            "sum-fields",
            1,
            approved=True,
            now_ms=1_200,
        )

        restarted = SkillRegistry(self.path)
        selected = restarted.selected_skill(self.identity, "synthetic_json_sum")
        self.assertIsNotNone(selected)
        self.assertEqual("sum-fields", selected["skill_id"])
        result = restarted.execute_for_family(
            self.identity,
            "synthetic_json_sum",
            {"a": 20, "b": 22},
        )
        self.assertEqual({"value": 42}, result["execution"]["result"])
        self.assertFalse(result["llm_used"])

    def test_unknown_family_fails_closed(self) -> None:
        with self.assertRaisesRegex(
            LookupError,
            "skill_registry_no_active_skill_for_family",
        ):
            self.registry.execute_for_family(
                self.identity,
                "unknown_family",
                {"a": 1, "b": 2},
            )

    def test_same_version_with_different_body_is_rejected(self) -> None:
        first = self.sum_skill()
        self.registry.register_developer_skill(
            self.identity,
            first,
            now_ms=1_100,
        )
        conflicting = self.sum_skill()
        conflicting["body"] = {"op": "literal", "args": [{"value": 999}]}
        with self.assertRaisesRegex(ValueError, "skill_registry_version_conflict"):
            self.registry.register_developer_skill(
                self.identity,
                conflicting,
                now_ms=1_200,
            )

    def test_identity_binding_rejects_other_identity(self) -> None:
        self.registry.register_developer_skill(
            self.identity,
            self.sum_skill(),
            now_ms=1_100,
        )
        with self.assertRaisesRegex(ValueError, "skill_registry_identity_mismatch"):
            self.registry.selected_skill("other-jade", "synthetic_json_sum")

    def test_status_is_explicit_about_non_learning_boundary(self) -> None:
        status = self.registry.status()
        self.assertTrue(status["developer_authored_only"])
        self.assertTrue(status["activation_requires_explicit_approval"])
        self.assertFalse(status["activation_automatic"])
        self.assertFalse(status["generated_skill_registration"])
        self.assertFalse(status["generated_skill_execution"])
        self.assertFalse(status["external_teacher_execution"])
        self.assertTrue(status["exact_family_selection"])
        self.assertFalse(status["fuzzy_recall"])
        self.assertFalse(status["dependencies_allowed"])
        self.assertFalse(status["llm_used_for_selection"])
        self.assertFalse(status["network_access"])


if __name__ == "__main__":
    unittest.main()
