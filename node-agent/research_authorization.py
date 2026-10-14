"""One-use local research permissions with a durable, hash-chained audit.

This is a local operator record, not a signature or proof of human identity.
The caller must present the exact plan to the operator before grant(). Nothing
from a research packet, model, or source document grants permission.
"""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import time
from typing import Any, Callable
import uuid

from shared_store_lock import atomic_write_text, shared_path_lock

SCHEMA = "jade.local_research_authorizations.v1"
MAX_EVENTS = 2000
MAX_STORE_BYTES = 2_000_000
ACTION_IDS = frozenset({
    "OFFLINE_ARTIFACT_RECHECK", "BLENDER_SYNTHETIC_FBX_ROUNDTRIP",
    "OFFICIAL_SOURCE_COLLECTION",
})


class AuthorizationRejected(ValueError):
    pass


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def plan_hash(plan: dict) -> str:
    if (not isinstance(plan, dict) or plan.get("schema") != "jade.local_experiment_plan.v1"
            or plan.get("action_id") not in ACTION_IDS
            or plan.get("scope") != "PC_ISOLATED_COPY"
            or plan.get("automatic_promotion") is not False
            or len(canonical(plan).encode("utf-8")) > 16_000):
        raise AuthorizationRejected("Invalid or unbounded experiment plan")
    return digest(plan)


def official_collection_plan(goal: str) -> dict:
    from goal_source_research import select_sources
    from official_source_capture import APPROVED
    keys, _ = select_sources(goal)
    return {
        "schema": "jade.local_experiment_plan.v1",
        "action_id": "OFFICIAL_SOURCE_COLLECTION", "scope": "PC_ISOLATED_COPY",
        "goal_sha256": hashlib.sha256(goal.strip().encode("utf-8")).hexdigest(),
        "urls": [APPROVED[key].url for key in keys],
        "network": "PINNED_PUBLIC_HTTPS_ONLY", "goal_sent_to_network": False,
        "max_response_bytes_per_source": 1_048_576, "timeout_seconds_per_source": 10,
        "automatic_promotion": False,
    }


@contextmanager
def _process_lock(path: Path):
    """OS locks release on process death; serialize separate local servers too."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with shared_path_lock(path), path.open("a+b") as handle:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class ResearchAuthorizationStore:
    def __init__(self, path: Path, *, clock: Callable[[], int] | None = None):
        self.path = Path(path)
        self.clock = clock or (lambda: int(time.time() * 1000))

    def _load(self) -> list[dict]:
        if not self.path.exists():
            return []
        try:
            with self.path.open("rb") as handle:
                raw = handle.read(MAX_STORE_BYTES + 1)
            if len(raw) > MAX_STORE_BYTES:
                raise AuthorizationRejected("Audit size limit reached")
            root = json.loads(raw)
            if root.get("schema") != SCHEMA or not isinstance(root.get("events"), list):
                raise AuthorizationRejected("Invalid authorization journal")
            rows = root["events"]
            if len(rows) > MAX_EVENTS:
                raise AuthorizationRejected("Audit event limit reached")
            previous = "0" * 64
            states = {}
            for index, row in enumerate(rows):
                body = {key: value for key, value in row.items() if key != "sha256"}
                if (row.get("sequence") != index + 1 or row.get("previous_sha256") != previous
                        or digest(body) != row.get("sha256")
                        or type(row.get("at_ms")) is not int):
                    raise AuthorizationRejected("Authorization audit integrity failure")
                previous = row["sha256"]
                kind, grant = row["kind"], row.get("authorization_id")
                if kind == "GRANTED":
                    if (not grant or grant in states or plan_hash(row["plan"]) != row["plan_sha256"]
                            or not 0 < row["expires_at_ms"] - row["at_ms"] <= 900_000):
                        raise AuthorizationRejected("Invalid grant")
                    states[grant] = kind
                elif kind in ("REVOKED", "STARTED"):
                    if states.get(grant) != "GRANTED":
                        raise AuthorizationRejected("Invalid authorization transition")
                    states[grant] = kind
                elif kind in ("COMPLETED", "FAILED"):
                    if states.get(grant) != "STARTED":
                        raise AuthorizationRejected("Invalid execution transition")
                    states[grant] = kind
                elif kind != "INSPECTION_RECORDED":
                    raise AuthorizationRejected("Unknown audit event")
            return rows
        except (OSError, KeyError, TypeError, ValueError, AttributeError) as exc:
            if isinstance(exc, AuthorizationRejected):
                raise
            raise AuthorizationRejected("Unreadable authorization journal") from exc

    def _append(self, rows: list[dict], kind: str, **fields) -> dict:
        if len(rows) >= MAX_EVENTS:
            raise AuthorizationRejected("Audit event limit reached")
        event = {
            "sequence": len(rows) + 1, "previous_sha256": rows[-1]["sha256"] if rows else "0" * 64,
            "kind": kind, "at_ms": self.clock(), **fields,
        }
        event["sha256"] = digest(event)
        content = canonical({"schema": SCHEMA, "events": rows + [event]}) + "\n"
        if len(content.encode("utf-8")) > MAX_STORE_BYTES:
            raise AuthorizationRejected("Audit size limit reached")
        atomic_write_text(self.path, content, mode=0o600)
        return event

    def events(self) -> list[dict]:
        with _process_lock(self.path.with_suffix(".lock")):
            return self._load()

    def grant(self, plan: dict, *, reviewer: str, note: str, confirmed: bool,
              ttl_ms: int = 900_000) -> dict:
        ph = plan_hash(plan)
        if (confirmed is not True or not isinstance(reviewer, str) or not 1 <= len(reviewer.strip()) <= 80
                or not isinstance(note, str) or not 3 <= len(note.strip()) <= 500
                or type(ttl_ms) is not int or not 0 < ttl_ms <= 900_000):
            raise AuthorizationRejected("Explicit confirmation, reviewer and reason required")
        with _process_lock(self.path.with_suffix(".lock")):
            rows = self._load()
            return self._append(rows, "GRANTED", authorization_id=str(uuid.uuid4()),
                                plan=plan, plan_sha256=ph, reviewer=reviewer.strip(), note=note.strip(),
                                expires_at_ms=self.clock() + ttl_ms)

    def _pending(self, rows: list[dict], authorization_id: str) -> dict:
        relevant = [row for row in rows if row.get("authorization_id") == authorization_id]
        if not relevant or relevant[-1]["kind"] != "GRANTED":
            raise AuthorizationRejected("Authorization missing, revoked or already used")
        grant = relevant[0]
        now = self.clock()
        if now < grant["at_ms"] or now >= grant["expires_at_ms"]:
            raise AuthorizationRejected("Authorization expired or clock moved backwards")
        return grant

    def revoke(self, authorization_id: str) -> dict:
        with _process_lock(self.path.with_suffix(".lock")):
            rows = self._load()
            # Expired permissions can also be explicitly revoked for the audit.
            relevant = [r for r in rows if r.get("authorization_id") == authorization_id]
            if not relevant or relevant[-1]["kind"] != "GRANTED":
                raise AuthorizationRejected("Authorization no longer revocable")
            return self._append(rows, "REVOKED", authorization_id=authorization_id)

    def execute(self, plan: dict, authorization_id: str, runner: Callable[[], Any]) -> Any:
        ph = plan_hash(plan)
        with _process_lock(self.path.with_suffix(".lock")):
            rows = self._load()
            grant = self._pending(rows, authorization_id)
            if grant["plan_sha256"] != ph:
                raise AuthorizationRejected("Plan or evidence changed: new review required")
            # Reserve room for a terminal event before executing anything.
            if len(rows) > MAX_EVENTS - 2 or self.path.stat().st_size > MAX_STORE_BYTES - 16_000:
                raise AuthorizationRejected("Insufficient audit capacity for execution")
            self._append(rows, "STARTED", authorization_id=authorization_id, plan_sha256=ph)
        # Consumption is durable before the first effect. Crashes leave STARTED
        # (unknown outcome), never a reusable grant. Never hold a lock during tools.
        try:
            result = runner()
            result_hash = digest(result)
        except BaseException as exc:
            with _process_lock(self.path.with_suffix(".lock")):
                self._append(self._load(), "FAILED", authorization_id=authorization_id,
                             error_type=type(exc).__name__)
            raise
        with _process_lock(self.path.with_suffix(".lock")):
            summary = {}
            if isinstance(result, dict):
                for key in ("output_directory", "result_file_sha256", "evaluation_status"):
                    value = result.get(key)
                    if isinstance(value, str) and len(value) <= 600:
                        summary[key] = value
            self._append(self._load(), "COMPLETED", authorization_id=authorization_id,
                         result_sha256=result_hash, result_summary=summary)
        return result

    def record_inspection(self, *, snapshot_sha256: str, source_sha256: str,
                          reviewer: str, note: str, stance: str) -> dict:
        if (stance not in {"UNCERTAIN", "SUPPORTS", "CONTRADICTS"}
                or any(not isinstance(v, str) or len(v) != 64
                       or any(c not in "0123456789abcdef" for c in v)
                       for v in (snapshot_sha256, source_sha256))
                or not isinstance(reviewer, str) or not 1 <= len(reviewer.strip()) <= 80
                or not isinstance(note, str) or not 3 <= len(note.strip()) <= 500):
            raise AuthorizationRejected("Invalid human inspection")
        with _process_lock(self.path.with_suffix(".lock")):
            return self._append(self._load(), "INSPECTION_RECORDED",
                                snapshot_sha256=snapshot_sha256, source_sha256=source_sha256,
                                reviewer=reviewer.strip(), note=note.strip(), stance=stance,
                                knowledge_verified=False, automatic_promotion=False)
