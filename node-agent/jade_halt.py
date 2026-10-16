"""Local persistent emergency halt for autonomous Jade node-agent loops.

This is a cooperative, node-local switch, not a process kill or a distributed
stop. State uses the repository's shared path lock and atomic file replacement.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any, Callable, Iterable

from shared_store_lock import (
    atomic_copy_file, atomic_write_text, ensure_private_store_path, shared_path_lock,
)

CONFIG_DIR = Path(os.environ.get("JADE_GENESIS_CONFIG_DIR", str(Path.home() / ".jade-genesis")))
STATE_PATH = CONFIG_DIR / "jade-halt.json"
SCHEMA = 1
INTERRUPTED = "INTERRUPTED_BY_HALT"


class HaltInterrupted(BaseException):
    """A cooperative boundary stopped execution without counting an error."""

    def __init__(self, loop: str, step: str, *, initial: bool):
        super().__init__(INTERRUPTED)
        self.loop = loop
        self.step = step
        self.initial = initial


class JadeHalt:
    def __init__(self, path: Path = STATE_PATH, *, event_sink: Callable | None = None):
        self.path = Path(path)
        self.backup_path = self.path.with_suffix(self.path.suffix + ".bak")
        self.journal_path = self.path.with_suffix(".jsonl")
        self.lock = shared_path_lock(self.path)
        self.event_sink = event_sink
        self._unreadable_logged = False

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {"schema_version": SCHEMA, "state": "RUNNING", "reason": "",
                "source": "", "at_ms": 0, "sealed_access_violations": 0,
                "consecutive_protected_failures": 0}

    def _event(self, kind: str, **fields: Any) -> None:
        # Never log externally supplied queries, hidden test cases, or tokens.
        record = {"kind": kind, "at_ms": int(time.time() * 1000),
                  **{k: v for k, v in fields.items()
                     if k in {"loop", "step", "source", "state", "reason",
                              "detector", "operator", "threshold"}}}
        ensure_private_store_path(self.journal_path)
        data = json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n"
        flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
        fd = os.open(self.journal_path, flags, 0o600)
        try:
            os.write(fd, data.encode("utf-8"))
            os.fsync(fd)
        finally:
            os.close(fd)
        if self.event_sink:
            self.event_sink(record)

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return self._empty()
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA:
                raise ValueError("invalid_schema")
            if raw.get("state") not in {"RUNNING", "HALTED"}:
                raise ValueError("invalid_state")
            if not isinstance(raw.get("reason"), str) or not isinstance(raw.get("source"), str):
                raise ValueError("invalid_metadata")
            for key in ("at_ms", "sealed_access_violations", "consecutive_protected_failures"):
                if not isinstance(raw.get(key), int) or raw[key] < 0:
                    raise ValueError("invalid_count")
            return raw
        except (OSError, UnicodeError, ValueError, TypeError):
            if not self._unreadable_logged:
                self._unreadable_logged = True
                self._event("halt_state_unreadable", state="HALTED")
            return {"schema_version": SCHEMA, "state": "HALTED",
                    "reason": "halt_state_unreadable", "source": "AUTO:corrupt_state",
                    "at_ms": int(time.time() * 1000),
                    "sealed_access_violations": 0, "consecutive_protected_failures": 0}

    def _save(self, state: dict[str, Any]) -> None:
        ensure_private_store_path(self.path)
        # The backup is for forensic recovery, never used for automatic rearming.
        if self.path.is_file():
            atomic_copy_file(self.path, self.backup_path, mode=0o600)
        atomic_write_text(self.path, json.dumps(state, sort_keys=True) + "\n", mode=0o600)

    def status(self) -> dict[str, Any]:
        with self.lock:
            return dict(self._load())

    def is_halted(self) -> bool:
        return self.status()["state"] == "HALTED"

    def halt(self, reason: str, *, source: str = "MANUAL") -> dict[str, Any]:
        if source != "MANUAL" and not (source.startswith("AUTO:") and len(source) > 5):
            raise ValueError("halt_source_invalid")
        clean_reason = str(reason).strip()[:180]
        if not clean_reason:
            raise ValueError("halt_reason_required")
        with self.lock:
            state = self._load()
            if state["state"] == "HALTED":
                return dict(state)
            state.update(state="HALTED", reason=clean_reason, source=source,
                         at_ms=int(time.time() * 1000))
            self._save(state)
            self._event("halt_transition", state="HALTED", source=source,
                        reason=clean_reason)
            return dict(state)

    def rearm(self, *, explicit_approval: bool, operator: str, reason: str) -> dict[str, Any]:
        if explicit_approval is not True:
            raise PermissionError("explicit_human_rearm_required")
        clean_operator, clean_reason = str(operator).strip()[:100], str(reason).strip()[:180]
        if not clean_operator or not clean_reason:
            raise PermissionError("named_operator_and_reason_required")
        with self.lock:
            state = self._load()
            if state["state"] == "RUNNING":
                return dict(state)
            state.update(state="RUNNING", source="MANUAL", reason=clean_reason,
                         at_ms=int(time.time() * 1000),
                         sealed_access_violations=0, consecutive_protected_failures=0)
            self._save(state)
            self._event("halt_transition", state="RUNNING", source="MANUAL",
                        operator=clean_operator, reason=clean_reason)
            return dict(state)

    def network_violation(self, *, runner: str, operation: str) -> None:
        # Operations are allowlisted symbols, never hostnames/URLs/tokens.
        clean = operation if operation in {
            "socket", "create_connection", "getaddrinfo",
            "gethostbyname", "gethostbyname_ex", "getnameinfo"
        } else "unknown"
        with self.lock:
            self._event("network_guard_violation", loop=runner, step=clean)
            self.halt("network_guard_violation", source="AUTO:network_guard")

    def checkpoint(self, loop: str, step: str = "entry", *, initial: bool = False) -> None:
        with self.lock:
            if self._load()["state"] == "HALTED":
                self._event("halt_blocked" if initial else "halt_interrupted",
                            loop=loop, step=step)
                raise HaltInterrupted(loop, step, initial=initial)

    @staticmethod
    def _threshold(value: int) -> int:
        return max(2, min(20, int(value)))

    def record_sealed_access(self, *, final_exam: bool, threshold: int = 2) -> None:
        """Only unauthorized repeated attempts contribute; never read SEALED_TEST."""
        with self.lock:
            state = self._load()
            if state["state"] == "HALTED":
                return
            state["sealed_access_violations"] = (
                0 if final_exam else state["sealed_access_violations"] + 1
            )
            if not final_exam and state["sealed_access_violations"] >= self._threshold(threshold):
                self.halt("repeated_sealed_access", source="AUTO:sealed_access")
            else:
                self._save(state)

    def record_protected_failure(self, *, failed: bool,
                                 threshold: int = 3) -> None:
        with self.lock:
            state = self._load()
            if state["state"] == "HALTED":
                return
            state["consecutive_protected_failures"] = (
                state["consecutive_protected_failures"] + 1 if failed else 0
            )
            if failed and state["consecutive_protected_failures"] >= self._threshold(threshold):
                self.halt("repeated_protected_failure", source="AUTO:protected_failures")
            else:
                self._save(state)


_GLOBAL_HALT = JadeHalt()


def checkpoint(loop: str, step: str = "entry", *, initial: bool = False) -> None:
    _GLOBAL_HALT.checkpoint(loop, step, initial=initial)


def interrupted_result(exc: HaltInterrupted) -> dict[str, Any]:
    return {"status": INTERRUPTED, "reason": INTERRUPTED,
            "loop": exc.loop, "step": exc.step, "automatic_retry": False}


def run_steps(steps: Iterable[tuple[str, Callable[[], Any]]],
              *, halt: JadeHalt, loop: str) -> list[Any]:
    """Testing-oriented sequence: each completed step commits only its own result."""
    output = []
    halt.checkpoint(loop, initial=True)
    for name, action in steps:
        halt.checkpoint(loop, name)
        value = action()
        halt.checkpoint(loop, name + ":completed")
        output.append(value)
    return output


def main(argv: list[str] | None = None, *, halt: JadeHalt | None = None) -> int:
    """Local operator CLI. Rearming requires a terminal and typed confirmation.

    This is not exposed to the Node Runtime API and does not authenticate a
    remote user. OS access to the node is a prerequisite for running the CLI.
    """
    import argparse
    import sys

    parser = argparse.ArgumentParser(
        description="Manage Jade's node-local cooperative emergency stop."
    )
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("status", help="Read the persisted local HALT state.")
    stop = actions.add_parser("halt", help="Stop autonomous local loops.")
    stop.add_argument("--reason", required=True, help="Reason for the stop.")
    resume = actions.add_parser("rearm", help="Manually resume after review.")
    resume.add_argument("--operator", required=True, help="Named human operator.")
    resume.add_argument("--reason", required=True, help="Reason for resuming.")
    args = parser.parse_args(argv)
    controller = halt if halt is not None else JadeHalt()

    if args.action == "status":
        result = controller.status()
    elif args.action == "halt":
        result = controller.halt(args.reason, source="MANUAL")
    else:
        # No --yes flag or environment variable may bypass this check.
        if not sys.stdin.isatty():
            print("Rearm refused: interactive terminal required.", file=sys.stderr)
            return 2
        try:
            typed = input("Type REARM to confirm local autonomous resume: ")
        except (EOFError, KeyboardInterrupt):
            print("Rearm cancelled.", file=sys.stderr)
            return 2
        if typed.strip() != "REARM":
            print("Rearm cancelled: confirmation did not match.", file=sys.stderr)
            return 2
        try:
            result = controller.rearm(
                explicit_approval=True, operator=args.operator, reason=args.reason
            )
        except PermissionError as exc:
            print(f"Rearm refused: {exc}", file=sys.stderr)
            return 2

    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
