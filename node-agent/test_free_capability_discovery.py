from __future__ import annotations

import unittest

from free_capability_discovery import (
    _is_loopback_http_url,
    discover_free_capabilities,
)


class FreeCapabilityDiscoveryTest(unittest.TestCase):
    def test_discovers_path_tools_and_existing_ollama_health(self):
        paths = {
            "ffmpeg": "/usr/bin/ffmpeg",
            "blender": "/opt/blender/blender",
            "playwright": "/usr/local/bin/playwright",
        }

        inventory = discover_free_capabilities(
            ollama_ready=True,
            ollama_model="qwen3:4b",
            comfyui_url="http://127.0.0.1:8188",
            which=lambda command: paths.get(command),
            http_probe=lambda url, timeout: url.endswith("/system_stats"),
            captured_at_ms=1234,
        )

        self.assertEqual(1, inventory["schema_version"])
        self.assertEqual("LOCAL_FREE_ONLY", inventory["policy"])
        self.assertTrue(inventory["read_only_discovery"])
        self.assertFalse(inventory["automatic_installation"])
        self.assertEqual(0, inventory["paid_provider_count"])
        self.assertEqual(1234, inventory["captured_at"])

        available = set(inventory["available_ids"])
        self.assertIn("ollama-local", available)
        self.assertIn("comfyui-local", available)
        self.assertIn("ffmpeg-local", available)
        self.assertIn("blender-local", available)
        self.assertIn("playwright-local", available)
        self.assertNotIn("piper-local", available)

        items = {item["id"]: item for item in inventory["items"]}
        self.assertEqual("qwen3:4b", items["ollama-local"]["details"]["model"])
        self.assertEqual(
            "/usr/bin/ffmpeg",
            items["ffmpeg-local"]["details"]["executable_path"],
        )
        self.assertEqual(
            "http://127.0.0.1:8188",
            items["comfyui-local"]["details"]["service_url"],
        )
        self.assertTrue(
            all(item["cost_class"] == "LOCAL_FREE" for item in inventory["items"])
        )

    def test_non_loopback_comfyui_probe_is_rejected(self):
        calls = []

        inventory = discover_free_capabilities(
            comfyui_url="https://example.com:8188",
            which=lambda command: None,
            http_probe=lambda url, timeout: calls.append((url, timeout)) or True,
            captured_at_ms=1,
        )

        self.assertEqual([], calls)
        items = {item["id"]: item for item in inventory["items"]}
        comfy = items["comfyui-local"]
        self.assertFalse(comfy["available"])
        self.assertIn("non_loopback_probe_rejected", comfy["evidence"])

    def test_loopback_url_guard(self):
        self.assertTrue(_is_loopback_http_url("http://127.0.0.1:8188"))
        self.assertTrue(_is_loopback_http_url("http://localhost:8188"))
        self.assertTrue(_is_loopback_http_url("http://[::1]:8188"))
        self.assertFalse(_is_loopback_http_url("https://example.com"))
        self.assertFalse(_is_loopback_http_url("file:///tmp/test"))

    def test_no_discovery_path_can_create_paid_entry(self):
        inventory = discover_free_capabilities(
            ollama_ready=True,
            which=lambda command: f"/fake/{command}",
            http_probe=lambda url, timeout: True,
            captured_at_ms=1,
        )

        self.assertEqual(0, inventory["paid_provider_count"])
        for item in inventory["items"]:
            self.assertEqual("LOCAL_FREE", item["cost_class"])


if __name__ == "__main__":
    unittest.main()
