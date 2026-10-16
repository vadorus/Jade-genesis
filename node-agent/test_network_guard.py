"""Offline tests for socket/DNS guard, including a spawned child.

No real network connections, DNS lookups, user data or live nodes are used.
"""
import multiprocessing
from pathlib import Path
import socket
import tempfile
import threading
import unittest

from jade_halt import JadeHalt
from network_guard import NetworkGuardViolation, no_python_network


def _spawned_probe(send, operation: str):
    # urllib imports ssl which subclasses socket.socket; load it before the guard.
    import urllib.request
    try:
        with no_python_network():
            if operation == "HTTP":
                urllib.request.urlopen("http://invalid.invalid", timeout=0.1)
            elif operation == "SOCKET":
                socket.socket()
            elif operation == "DNS":
                socket.getaddrinfo("invalid.invalid", 443)
        send.send(("NO_VIOLATION", ""))
    except NetworkGuardViolation as exc:
        send.send(("NETWORK", exc.operation))
    finally:
        send.close()


class NetworkGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.halt = JadeHalt(Path(self.temp.name) / "halt.json")

    def test_http_denied_without_network(self):
        import urllib.request
        with self.assertRaises(NetworkGuardViolation):
            with no_python_network():
                urllib.request.urlopen("http://invalid.invalid", timeout=0.1)

    def test_direct_socket_denied(self):
        with no_python_network():
            with self.assertRaises(NetworkGuardViolation):
                socket.socket()

    def test_dns_denied(self):
        with no_python_network():
            with self.assertRaises(NetworkGuardViolation):
                socket.getaddrinfo("invalid.invalid", 443)
            with self.assertRaises(NetworkGuardViolation):
                socket.gethostbyname("invalid.invalid")

    def test_restoration_after_normal_exit(self):
        original_socket, original_dns = socket.socket, socket.getaddrinfo
        with no_python_network():
            self.assertIsNot(original_socket, socket.socket)
            self.assertIsNot(original_dns, socket.getaddrinfo)
        self.assertIs(original_socket, socket.socket)
        self.assertIs(original_dns, socket.getaddrinfo)

    def test_restoration_after_exception(self):
        original_socket = socket.socket
        original_dns = socket.gethostbyname
        with self.assertRaises(ValueError):
            with no_python_network():
                raise ValueError("synthetic")
        self.assertIs(original_socket, socket.socket)
        self.assertIs(original_dns, socket.gethostbyname)

    def test_violation_logs_and_halts(self):
        with self.assertRaises(NetworkGuardViolation):
            with no_python_network(on_violation=lambda exc: self.halt.network_violation(
                runner="test_runner", operation=exc.operation
            )):
                socket.getaddrinfo("invalid.invalid", 443)
        self.assertTrue(self.halt.is_halted())
        audit = self.halt.journal_path.read_text(encoding="utf-8")
        self.assertIn("network_guard_violation", audit)
        self.assertNotIn("invalid.invalid", audit)

    def test_guard_not_installed_globally_in_multithreaded_parent(self):
        original = socket.socket
        ready = threading.Event()
        done = threading.Event()
        def other():
            ready.set()
            done.wait(timeout=5)
        thread = threading.Thread(target=other)
        thread.start()
        try:
            self.assertTrue(ready.wait(timeout=2))
            with self.assertRaises(RuntimeError):
                with no_python_network():
                    pass
            self.assertIs(original, socket.socket)
        finally:
            done.set()
            thread.join(timeout=5)

    def test_spawned_child_detects_http_socket_dns_and_parent_unaffected(self):
        ctx = multiprocessing.get_context("spawn")
        original_socket = socket.socket
        for operation in ("HTTP", "SOCKET", "DNS"):
            with self.subTest(operation=operation):
                receive, send = ctx.Pipe(duplex=False)
                child = ctx.Process(target=_spawned_probe, args=(send, operation))
                child.start()
                send.close()
                try:
                    self.assertTrue(receive.poll(15), operation)
                    status, symbol = receive.recv()
                    child.join(timeout=5)
                    self.assertEqual(0, child.exitcode)
                    self.assertEqual("NETWORK", status)
                    self.halt.network_violation(runner="test_spawn", operation=symbol)
                    self.assertTrue(self.halt.is_halted())
                    self.assertIs(original_socket, socket.socket)
                finally:
                    receive.close()
                    if child.is_alive():
                        child.terminate()
                        child.join(timeout=5)

if __name__ == "__main__":
    unittest.main()
