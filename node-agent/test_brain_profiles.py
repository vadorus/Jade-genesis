from __future__ import annotations

import unittest

from brain_profiles import (
    profile_from_operation,
    profile_options,
    select_profile_model,
)


class BrainProfilesTest(unittest.TestCase):
    def setUp(self) -> None:
        gib = 1024 ** 3
        self.models = [
            {
                "name": "qwen2.5:3b",
                "size": int(2.2 * gib),
                "details": {"parameter_size": "3B"},
            },
            {
                "name": "qwen2.5-coder:7b",
                "size": int(4.8 * gib),
                "details": {"parameter_size": "7B"},
            },
            {
                "name": "qwen3:8b",
                "size": int(5.4 * gib),
                "details": {"parameter_size": "8B"},
            },
            {
                "name": "deepseek-r1:14b",
                "size": int(9.2 * gib),
                "details": {"parameter_size": "14B"},
            },
        ]

    def test_operation_forces_specialized_profiles(self) -> None:
        self.assertEqual(profile_from_operation("verify", "fast"), "critic")
        self.assertEqual(profile_from_operation("revise", "fast"), "reasoning")
        self.assertEqual(profile_from_operation("tool_build", "general"), "code")

    def test_fast_prefers_small_model(self) -> None:
        selected = select_profile_model({}, self.models, "fast", gpu_vram_free_gb=6.0)
        self.assertIsNotNone(selected)
        self.assertEqual(selected["model"], "qwen2.5:3b")
        self.assertFalse(selected["degraded"])

    def test_general_does_not_prefer_3b_as_central_brain(self) -> None:
        selected = select_profile_model({}, self.models, "general", gpu_vram_free_gb=6.0)
        self.assertIsNotNone(selected)
        self.assertEqual(selected["model"], "qwen3:8b")
        self.assertFalse(selected["degraded"])

    def test_reasoning_prefers_reasoning_model_when_resources_allow(self) -> None:
        selected = select_profile_model({}, self.models, "reasoning", gpu_vram_free_gb=12.0)
        self.assertIsNotNone(selected)
        self.assertEqual(selected["model"], "deepseek-r1:14b")
        self.assertFalse(selected["degraded"])

    def test_code_prefers_coder(self) -> None:
        selected = select_profile_model({}, self.models, "code", gpu_vram_free_gb=6.0)
        self.assertIsNotNone(selected)
        self.assertEqual(selected["model"], "qwen2.5-coder:7b")

    def test_small_only_general_is_marked_degraded(self) -> None:
        selected = select_profile_model({}, [self.models[0]], "general", gpu_vram_free_gb=6.0)
        self.assertIsNotNone(selected)
        self.assertEqual(selected["model"], "qwen2.5:3b")
        self.assertTrue(selected["degraded"])

    def test_explicit_profile_override_is_respected(self) -> None:
        selected = select_profile_model(
            {"brain_model_code": "qwen3:8b"},
            self.models,
            "code",
            gpu_vram_free_gb=6.0,
        )
        self.assertIsNotNone(selected)
        self.assertEqual(selected["model"], "qwen3:8b")
        self.assertEqual(selected["reason"], "configured:qwen3:8b")

    def test_reasoning_uses_larger_context_than_fast(self) -> None:
        self.assertGreater(
            profile_options("reasoning")["num_ctx"],
            profile_options("fast")["num_ctx"],
        )
        self.assertLess(
            profile_options("critic")["temperature"],
            profile_options("general")["temperature"],
        )


if __name__ == "__main__":
    unittest.main()
