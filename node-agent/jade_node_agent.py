#!/usr/bin/env python3
"""
Jade Genesis Distributed Node Runtime 0.0.5

Dependency-free development runtime for Windows/Linux/macOS.
It exposes:
- GET /health: authenticated node profile
- POST /task: authenticated allow-listed distributed tasks

Allowed tasks in V0.0.5:
- genesis_probe: bounded SHA-256 compute probe
- text_analysis: deterministic text metrics + digest

It never executes arbitrary shell/system commands.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import hmac
import json
import os
import platform
import re
import secrets
import shutil
import socket
import sys
import time
import uuid
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

PROTOCOL = "jade-genesis-node/0.0.5"
VERSION = "0.0.5"
DEFAULT_PORT = 8765
MAX_BODY_BYTES = 96 * 1024
MAX_PAYLOAD_CHARS = 16_384
MAX_ITERATIONS = 100_000
CONFIG_DIR = Path.home() / ".jade-genesis"
CONFIG_PATH = CONFIG_DIR / "node-agent.json"
ALLOWED_TASKS = ("genesis_probe", "text_analysis")


def round_gb(value: int | float) -> float:
    return round(float(value) / (1024 ** 3), 2)


def load_or_create_config(port_override: int | None) -> dict[str, Any]:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)

    config: dict[str, Any] = {}
    if CONFIG_PATH.exists():
        try:
            config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:
            config = {}

    if not config.get("node_id"):
        config["node_id"] = f"pc-{uuid.uuid4()}"

    if not config.get("token"):
        config["token"] = secrets.token_urlsafe(24)

    if port_override is not None:
        config["port"] = port_override
    elif not isinstance(config.get("port"), int):
        config["port"] = DEFAULT_PORT

    CONFIG_PATH.write_text(
        json.dumps(config, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return config


def reset_token(config: dict[str, Any]) -> dict[str, Any]:
    config["token"] = secrets.token_urlsafe(24)
    CONFIG_PATH.write_text(
        json.dumps(config, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return config


def windows_memory() -> tuple[int, int] | None:
    if not sys.platform.startswith("win"):
        return None

    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [
            ("dwLength", ctypes.c_ulong),
            ("dwMemoryLoad", ctypes.c_ulong),
            ("ullTotalPhys", ctypes.c_ulonglong),
            ("ullAvailPhys", ctypes.c_ulonglong),
            ("ullTotalPageFile", ctypes.c_ulonglong),
            ("ullAvailPageFile", ctypes.c_ulonglong),
            ("ullTotalVirtual", ctypes.c_ulonglong),
            ("ullAvailVirtual", ctypes.c_ulonglong),
            ("sullAvailExtendedVirtual", ctypes.c_ulonglong),
        ]

    status = MEMORYSTATUSEX()
    status.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
    ok = ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
    if not ok:
        return None

    return int(status.ullTotalPhys), int(status.ullAvailPhys)


def linux_memory() -> tuple[int, int] | None:
    meminfo = Path("/proc/meminfo")
    if not meminfo.exists():
        return None

    values: dict[str, int] = {}
    for line in meminfo.read_text(encoding="utf-8").splitlines():
        if ":" not in line:
            continue
        key, raw = line.split(":", 1)
        parts = raw.strip().split()
        if not parts:
            continue
        try:
            values[key] = int(parts[0]) * 1024
        except ValueError:
            pass

    total = values.get("MemTotal")
    available = values.get("MemAvailable")
    if total is None or available is None:
        return None

    return total, available


def posix_memory() -> tuple[int, int] | None:
    try:
        page_size = os.sysconf("SC_PAGE_SIZE")
        total_pages = os.sysconf("SC_PHYS_PAGES")
        available_pages = os.sysconf("SC_AVPHYS_PAGES")
        return (
            int(page_size * total_pages),
            int(page_size * available_pages),
        )
    except (AttributeError, ValueError, OSError):
        return None


def memory_bytes() -> tuple[int, int]:
    for probe in (windows_memory, linux_memory, posix_memory):
        result = probe()
        if result is not None:
            return result
    return 0, 0


def cpu_name() -> str:
    candidates = [
        os.environ.get("PROCESSOR_IDENTIFIER", ""),
        platform.processor(),
        platform.machine(),
    ]
    for candidate in candidates:
        value = candidate.strip()
        if value:
            return value
    return "Unknown CPU"


def local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return str(sock.getsockname()[0])
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"
    finally:
        sock.close()


def health_payload(config: dict[str, Any]) -> dict[str, Any]:
    total_ram, available_ram = memory_bytes()
    try:
        storage = shutil.disk_usage(Path.home())
        storage_free = storage.free
    except OSError:
        storage_free = 0

    hostname = socket.gethostname() or "PC Genesis"
    os_name = f"{platform.system()} {platform.release()}".strip()

    return {
        "protocol": PROTOCOL,
        "agent_version": VERSION,
        "node_id": config["node_id"],
        "name": f"PC — {hostname}",
        "kind": "PC",
        "status": "ONLINE",
        "os": os_name,
        "cpu": cpu_name(),
        "cpu_cores": os.cpu_count() or 1,
        "ram_total_gb": round_gb(total_ram) if total_ram else 0.0,
        "ram_available_gb": round_gb(available_ram) if available_ram else 0.0,
        "storage_free_gb": round_gb(storage_free) if storage_free else 0.0,
        "capabilities": [
            "node_runtime",
            "compute",
            "python_runtime",
            "hardware_profile",
            "task_execution_v1",
            "task_execution_v2",
            "genesis_probe",
            "text_analysis",
        ],
        "timestamp": int(time.time() * 1000),
    }


def run_genesis_probe(payload: str, iterations: int) -> tuple[str, int]:
    started = time.perf_counter_ns()
    data = payload.encode("utf-8")

    for _ in range(iterations):
        data = hashlib.sha256(data).digest()

    duration_ms = (time.perf_counter_ns() - started) // 1_000_000
    return data.hex(), int(duration_ms)


def run_text_analysis(payload: str) -> tuple[str, int]:
    started = time.perf_counter_ns()
    words = [
        match.group(0).lower()
        for match in re.finditer(r"[^\W_]+(?:['’\-][^\W_]+)*|\d+", payload, re.UNICODE)
    ]
    counts = Counter(words)
    top_terms = sorted(
        counts.items(),
        key=lambda item: (-item[1], item[0]),
    )[:5]

    result = {
        "characters": len(payload),
        "bytes_utf8": len(payload.encode("utf-8")),
        "lines": 0 if not payload else len(payload.splitlines()),
        "words": len(words),
        "unique_words": len(counts),
        "top_terms": ",".join(f"{word}:{count}" for word, count in top_terms),
        "sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
    }
    duration_ms = (time.perf_counter_ns() - started) // 1_000_000
    return json.dumps(result, ensure_ascii=False, separators=(",", ":")), int(duration_ms)


def execute_allowlisted_task(
    task_kind: str,
    payload: str,
    iterations: int,
) -> tuple[str, int]:
    if task_kind == "genesis_probe":
        if not (1 <= iterations <= MAX_ITERATIONS):
            raise ValueError("iterations_out_of_range")
        return run_genesis_probe(payload, iterations)

    if task_kind == "text_analysis":
        return run_text_analysis(payload)

    raise ValueError("unsupported_task")


def make_handler(config: dict[str, Any]):
    class JadeNodeHandler(BaseHTTPRequestHandler):
        server_version = f"JadeGenesisNode/{VERSION}"

        def _authorized(self) -> bool:
            supplied = self.headers.get("X-Jade-Token", "")
            expected = str(config["token"])
            return hmac.compare_digest(supplied, expected)

        def _send_json(self, status: int, body: dict[str, Any]) -> None:
            payload = json.dumps(
                body,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")

            self.send_response(status)
            self.send_header(
                "Content-Type",
                "application/json; charset=utf-8",
            )
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def _reject_if_unauthorized(self) -> bool:
            if self._authorized():
                return False
            self._send_json(401, {"error": "unauthorized"})
            return True

        def do_GET(self) -> None:
            if self.path != "/health":
                self._send_json(404, {"error": "not_found"})
                return

            if self._reject_if_unauthorized():
                return

            self._send_json(200, health_payload(config))

        def do_POST(self) -> None:
            if self.path != "/task":
                self._send_json(404, {"error": "not_found"})
                return

            if self._reject_if_unauthorized():
                return

            raw_length = self.headers.get("Content-Length", "")
            try:
                content_length = int(raw_length)
            except ValueError:
                self._send_json(411, {"error": "content_length_required"})
                return

            if content_length <= 0 or content_length > MAX_BODY_BYTES:
                self._send_json(413, {"error": "request_too_large"})
                return

            try:
                raw = self.rfile.read(content_length)
                request = json.loads(raw.decode("utf-8"))
            except Exception:
                self._send_json(400, {"error": "invalid_json"})
                return

            if request.get("protocol") != PROTOCOL:
                self._send_json(
                    409,
                    {
                        "error": "protocol_mismatch",
                        "protocol": PROTOCOL,
                    },
                )
                return

            task_id = str(request.get("task_id", "")).strip()
            task_kind = str(request.get("task_kind", "")).strip()
            payload = str(request.get("payload", ""))

            try:
                iterations = int(request.get("iterations", 0))
            except (TypeError, ValueError):
                iterations = 0

            if not task_id or len(task_id) > 120:
                self._send_json(400, {"error": "invalid_task_id"})
                return

            if task_kind not in ALLOWED_TASKS:
                self._send_json(
                    400,
                    {
                        "error": "unsupported_task",
                        "allowed": list(ALLOWED_TASKS),
                    },
                )
                return

            if len(payload) > MAX_PAYLOAD_CHARS:
                self._send_json(413, {"error": "payload_too_large"})
                return

            try:
                result, duration_ms = execute_allowlisted_task(
                    task_kind=task_kind,
                    payload=payload,
                    iterations=iterations,
                )
            except ValueError as exc:
                self._send_json(400, {"error": str(exc)})
                return
            except Exception as exc:
                self._send_json(
                    500,
                    {"error": f"task_failed:{exc.__class__.__name__}"},
                )
                return

            self._send_json(
                200,
                {
                    "protocol": PROTOCOL,
                    "agent_version": VERSION,
                    "task_id": task_id,
                    "task_kind": task_kind,
                    "success": True,
                    "node_id": config["node_id"],
                    "node_name": f"PC — {socket.gethostname() or 'home'}",
                    "result": result,
                    "duration_ms": duration_ms,
                    "completed_at": int(time.time() * 1000),
                },
            )

        def log_message(self, fmt: str, *args: Any) -> None:
            print(
                f"[{time.strftime('%H:%M:%S')}] "
                f"{self.client_address[0]} - {fmt % args}"
            )

    return JadeNodeHandler


def self_test() -> int:
    probe, _ = run_genesis_probe("jade-self-test", 50)
    if len(probe) != 64:
        print("SELF-TEST FAILED: genesis_probe", file=sys.stderr)
        return 1

    analysis_raw, _ = run_text_analysis("Jade Jade Genesis test")
    analysis = json.loads(analysis_raw)
    if analysis.get("words") != 4:
        print(f"SELF-TEST FAILED: text_analysis={analysis}", file=sys.stderr)
        return 1
    if analysis.get("unique_words") != 3:
        print(f"SELF-TEST FAILED: unique_words={analysis}", file=sys.stderr)
        return 1

    print("JADE NODE RUNTIME 0.0.5 SELF-TEST OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Jade Genesis Distributed Node Runtime 0.0.5"
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help=f"Port d'écoute (défaut persistant : {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--reset-token",
        action="store_true",
        help="Génère un nouveau jeton d'appairage.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Teste les tâches autorisées sans démarrer le serveur.",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.port is not None and not (1 <= args.port <= 65535):
        parser.error("Le port doit être compris entre 1 et 65535.")

    config = load_or_create_config(args.port)
    if args.reset_token:
        config = reset_token(config)

    port = int(config["port"])
    ip = local_ip()

    print()
    print("JADE GENESIS — DISTRIBUTED NODE RUNTIME 0.0.5")
    print("=" * 49)
    print(f"Node ID : {config['node_id']}")
    print(f"IP LAN  : {ip}")
    print(f"Port    : {port}")
    print(f"Jeton   : {config['token']}")
    print()
    print("Dans Jade Android > Node Manager :")
    print(f"  IP    = {ip}")
    print(f"  Port  = {port}")
    print(f"  Jeton = {config['token']}")
    print()
    print("Endpoints : GET /health, POST /task")
    print("Tâches autorisées : genesis_probe, text_analysis")
    print("Aucune commande système arbitraire n'est exposée.")
    print("Ctrl+C pour arrêter.")
    print()

    try:
        server = ThreadingHTTPServer(
            ("0.0.0.0", port),
            make_handler(config),
        )
        server.daemon_threads = True
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nArrêt du Distributed Node Runtime.")
    except OSError as exc:
        print(f"Erreur réseau : {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
