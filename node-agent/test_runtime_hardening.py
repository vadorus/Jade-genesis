from __future__ import annotations

import stat
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import jade_node_runtime_core as core


class RuntimeHardeningTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        root = Path(self.tempdir.name)
        self.config_dir = root / ".jade-genesis"
        self.config_path = self.config_dir / "node-agent.json"
        self.backup_path = self.config_dir / "node-agent.json.bak"

        patches = [
            mock.patch.object(core, "CONFIG_DIR", self.config_dir),
            mock.patch.object(core, "CONFIG_PATH", self.config_path),
            mock.patch.object(core, "CONFIG_BACKUP_PATH", self.backup_path),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_corrupted_primary_restores_backup_without_new_identity(self) -> None:
        first = core.load_or_create_config(None)
        second = core.load_or_create_config(None)
        self.assertEqual(first["node_id"], second["node_id"])
        self.assertEqual(first["token"], second["token"])
        self.assertTrue(self.backup_path.exists())

        self.config_path.write_text('{"node_id":', encoding="utf-8")
        restored = core.load_or_create_config(None)

        self.assertEqual(first["node_id"], restored["node_id"])
        self.assertEqual(first["token"], restored["token"])

    def test_corrupted_primary_and_backup_fail_closed(self) -> None:
        core.load_or_create_config(None)
        core.load_or_create_config(None)
        self.config_path.write_text("{", encoding="utf-8")
        self.backup_path.write_text("[", encoding="utf-8")

        with self.assertRaises(SystemExit):
            core.load_or_create_config(None)

    def test_config_is_private_on_posix(self) -> None:
        core.load_or_create_config(None)
        if hasattr(stat, "S_IMODE"):
            self.assertEqual(stat.S_IMODE(self.config_path.stat().st_mode), 0o600)

    def test_tailscale_bind_is_required(self) -> None:
        self.assertEqual(core.normalize_bind_address("100.64.1.23"), "100.64.1.23")
        for value in ("", "0.0.0.0", "192.168.1.10", "127.0.0.1", "::1"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    core.normalize_bind_address(value)

    def test_async_store_limits_parallel_execution(self) -> None:
        started = threading.Event()
        release = threading.Event()
        state_lock = threading.Lock()
        state = {"running": 0, "max_running": 0}

        def fake_execute(**_kwargs):
            with state_lock:
                state["running"] += 1
                state["max_running"] = max(state["max_running"], state["running"])
                started.set()
            try:
                if not release.wait(timeout=3):
                    raise RuntimeError("test release timeout")
                return "ok", 1
            finally:
                with state_lock:
                    state["running"] -= 1

        store = core.AsyncTaskStore({"max_parallel_tasks": 1})
        with mock.patch.object(core, "execute_allowlisted_task", side_effect=fake_execute):
            for index in range(4):
                store.submit(f"task-{index}", "text_analysis", "x", 0)

            self.assertTrue(started.wait(timeout=1))
            time.sleep(0.1)
            stats = store.stats()
            self.assertEqual(stats["running"], 1)
            self.assertEqual(stats["queued"], 3)
            self.assertEqual(state["max_running"], 1)

            release.set()
            deadline = time.time() + 3
            while time.time() < deadline and store.stats()["active"] != 0:
                time.sleep(0.02)
            self.assertEqual(store.stats()["active"], 0)
            self.assertEqual(state["max_running"], 1)

        store.executor.shutdown(wait=True, cancel_futures=True)

    def test_cleanup_never_drops_active_tasks(self) -> None:
        store = core.AsyncTaskStore({"max_parallel_tasks": 1})
        old = time.time() - core.ASYNC_TASK_TTL_SECONDS - 10
        with store.lock:
            store.tasks["running"] = {
                "status": "RUNNING",
                "updated_epoch": old,
            }
            for index in range(core.ASYNC_TASK_MAX_ITEMS + 5):
                store.tasks[f"done-{index}"] = {
                    "status": "COMPLETED",
                    "updated_epoch": old - index,
                }
            store._cleanup_locked()
            self.assertIn("running", store.tasks)
            self.assertLessEqual(
                sum(1 for item in store.tasks.values() if item["status"] == "COMPLETED"),
                core.ASYNC_TASK_MAX_ITEMS - 1,
            )
        store.executor.shutdown(wait=True, cancel_futures=True)

    def test_http_handler_has_connection_timeout(self) -> None:
        store = core.AsyncTaskStore({"max_parallel_tasks": 1})
        handler = core.make_handler({"token": "test-token"}, store)
        self.assertEqual(handler.timeout, 15)
        store.executor.shutdown(wait=True, cancel_futures=True)


if __name__ == "__main__":
    unittest.main()
