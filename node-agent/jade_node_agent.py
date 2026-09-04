#!/usr/bin/env python3
"""
Jade Genesis PC Node Agent 0.0.3

Dependency-free development agent for Windows/Linux/macOS.
It exposes only a read-only /health endpoint. It does NOT execute
remote tasks in V0.0.3.
"""

from __future__ import annotations

import argparse
import ctypes
import hmac
import json
import os
import platform
import secrets
import shutil
import socket
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

PROTOCOL = "jade-genesis-node/0.0.3"
VERSION = "0.0.3"
DEFAULT_PORT = 8765
CONFIG_DIR = Path.home() / ".jade-genesis"
CONFIG_PATH = CONFIG_DIR / "node-agent.json"


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
            "node_agent",
            "compute",
            "python_runtime",
            "hardware_profile",
        ],
        "timestamp": int(time.time() * 1000),
    }


def make_handler(config: dict[str, Any]):
    class JadeNodeHandler(BaseHTTPRequestHandler):
        server_version = f"JadeGenesisNode/{VERSION}"

        def do_GET(self) -> None:
            if self.path != "/health":
                self.send_error(404)
                return

            supplied = self.headers.get("X-Jade-Token", "")
            expected = str(config["token"])

            if not hmac.compare_digest(supplied, expected):
                self.send_response(401)
                self.send_header(
                    "Content-Type",
                    "application/json; charset=utf-8",
                )
                self.end_headers()
                self.wfile.write(b'{"error":"unauthorized"}')
                return

            payload = json.dumps(
                health_payload(config),
                ensure_ascii=False,
            ).encode("utf-8")

            self.send_response(200)
            self.send_header(
                "Content-Type",
                "application/json; charset=utf-8",
            )
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, fmt: str, *args: Any) -> None:
            print(
                f"[{time.strftime('%H:%M:%S')}] "
                f"{self.client_address[0]} - {fmt % args}"
            )

    return JadeNodeHandler


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Jade Genesis PC Node Agent 0.0.3"
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
    args = parser.parse_args()

    if args.port is not None and not (1 <= args.port <= 65535):
        parser.error("Le port doit être compris entre 1 et 65535.")

    config = load_or_create_config(args.port)
    if args.reset_token:
        config = reset_token(config)

    port = int(config["port"])
    ip = local_ip()

    print()
    print("JADE GENESIS — PC NODE AGENT 0.0.3")
    print("=" * 42)
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
    print("V0.0.3 expose uniquement /health.")
    print("Aucune tâche distante ne peut encore être exécutée.")
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
        print("\nArrêt du Node Agent.")
    except OSError as exc:
        print(f"Erreur réseau : {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
