"""Physical learning-environment boundaries for Jade Genesis 0.1.21.

The learning system is split into three roots under JADE_GENESIS_CONFIG_DIR:

- production/: minimal mutable routing state used to serve the user;
- workshop/: disposable learning goals, snapshots and candidate work;
- archive/: retained skills, sealed datasets and append-only attribution events.

The directories are intentionally distinct even when they live on the same VPS
filesystem. Higher-level stores must not collapse them back into one file.
"""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path
from typing import Any

CONFIG_DIR = Path(
    os.environ.get("JADE_GENESIS_CONFIG_DIR", str(Path.home() / ".jade-genesis"))
)
PRODUCTION_DIR = CONFIG_DIR / "production"
WORKSHOP_DIR = CONFIG_DIR / "workshop"
ARCHIVE_DIR = CONFIG_DIR / "archive"

PRODUCTION_SKILL_ROUTES_PATH = PRODUCTION_DIR / "skill-routes.json"
WORKSHOP_GOALS_PATH = WORKSHOP_DIR / "skill-learning-goals.json"
WORKSHOP_CANDIDATES_DIR = WORKSHOP_DIR / "candidates"
ARCHIVE_SKILL_REGISTRY_PATH = ARCHIVE_DIR / "skill-registry.json"
ARCHIVE_TASK_LEDGER_PATH = ARCHIVE_DIR / "verifiable-task-ledger.json"
ARCHIVE_CASE_PACK_DIR = ARCHIVE_DIR / "case-packs"
ARCHIVE_ATTRIBUTION_LOG_PATH = ARCHIVE_DIR / "skill-attribution.jsonl"

SCHEMA_VERSION = 1
_ATTRIBUTION_LOCK = threading.RLock()


def ensure_learning_environment() -> None:
    for path in (
        PRODUCTION_DIR,
        WORKSHOP_DIR,
        WORKSHOP_CANDIDATES_DIR,
        ARCHIVE_DIR,
        ARCHIVE_CASE_PACK_DIR,
    ):
        path.mkdir(parents=True, exist_ok=True)


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def append_attribution_event(
    event_kind: str,
    payload: dict[str, Any],
    *,
    path: Path = ARCHIVE_ATTRIBUTION_LOG_PATH,
    now_ms: int | None = None,
) -> dict[str, Any]:
    """Append one immutable learning/usage event to the archive journal.

    The log is append-only from Jade's point of view. Each record contains the
    previous record hash, creating a simple tamper-evident chain without making
    the mutable materialized stores themselves the historical record.
    """

    if not isinstance(payload, dict):
        raise ValueError("attribution_payload_must_be_object")
    clean_kind = " ".join(str(event_kind or "").split())[:120]
    if not clean_kind:
        raise ValueError("missing_attribution_event_kind")
    now = int(time.time() * 1_000) if now_ms is None else max(0, int(now_ms))

    with _ATTRIBUTION_LOCK:
        path.parent.mkdir(parents=True, exist_ok=True)
        previous_hash = "0" * 64
        sequence = 1
        if path.exists():
            try:
                with path.open("rb") as handle:
                    lines = [line for line in handle.read().splitlines() if line.strip()]
                if lines:
                    previous = json.loads(lines[-1].decode("utf-8"))
                    if isinstance(previous, dict):
                        candidate = str(previous.get("event_sha256", "")).lower()
                        if len(candidate) == 64:
                            previous_hash = candidate
                        sequence = max(1, int(previous.get("sequence", 0)) + 1)
            except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
                raise RuntimeError("attribution_archive_corrupt") from None

        body = {
            "schema_version": SCHEMA_VERSION,
            "sequence": sequence,
            "event_kind": clean_kind,
            "occurred_at": now,
            "previous_sha256": previous_hash,
            "payload": payload,
        }
        digest = hashlib.sha256(_canonical_json(body).encode("utf-8")).hexdigest()
        record = {**body, "event_sha256": digest}
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(_canonical_json(record))
            handle.write("\n")
            handle.flush()
            try:
                os.fsync(handle.fileno())
            except OSError:
                pass
        return record


def read_attribution_events(
    *,
    path: Path = ARCHIVE_ATTRIBUTION_LOG_PATH,
    limit: int = 10_000,
) -> list[dict[str, Any]]:
    """Read and verify the append-only hash chain."""

    if not path.exists():
        return []
    events: list[dict[str, Any]] = []
    previous_hash = "0" * 64
    expected_sequence = 1
    try:
        with path.open("r", encoding="utf-8") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line:
                    continue
                item = json.loads(line)
                if not isinstance(item, dict):
                    raise RuntimeError("attribution_archive_corrupt")
                digest = str(item.get("event_sha256", "")).lower()
                body = {key: value for key, value in item.items() if key != "event_sha256"}
                computed = hashlib.sha256(_canonical_json(body).encode("utf-8")).hexdigest()
                if digest != computed:
                    raise RuntimeError("attribution_archive_hash_mismatch")
                if str(item.get("previous_sha256", "")) != previous_hash:
                    raise RuntimeError("attribution_archive_chain_mismatch")
                if int(item.get("sequence", 0)) != expected_sequence:
                    raise RuntimeError("attribution_archive_sequence_mismatch")
                events.append(item)
                previous_hash = digest
                expected_sequence += 1
                if len(events) > max(1, int(limit)):
                    raise RuntimeError("attribution_archive_limit_exceeded")
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise RuntimeError("attribution_archive_corrupt") from exc
    return events


def attribution_summary(
    skill_id: str,
    skill_version: int,
    *,
    path: Path = ARCHIVE_ATTRIBUTION_LOG_PATH,
) -> dict[str, Any]:
    """Derive usage and measured-gain metadata from immutable events."""

    sid = str(skill_id or "").strip()
    version = int(skill_version)
    relevant = []
    for event in read_attribution_events(path=path):
        payload = event.get("payload", {})
        if not isinstance(payload, dict):
            continue
        if str(payload.get("skill_id", "")) == sid and int(payload.get("skill_version", 0)) == version:
            relevant.append(event)

    retained = next(
        (event for event in relevant if event.get("event_kind") == "skill_retained"),
        None,
    )
    usage_count = sum(1 for event in relevant if event.get("event_kind") == "skill_used")
    payload = retained.get("payload", {}) if isinstance(retained, dict) else {}
    return {
        "skill_id": sid,
        "skill_version": version,
        "provenance": payload.get("provenance", {}),
        "usage_count": usage_count,
        "measured_gain": payload.get("measured_gain", {}),
        "retained_at": retained.get("occurred_at", 0) if isinstance(retained, dict) else 0,
        "event_count": len(relevant),
    }


def learning_environment_status() -> dict[str, Any]:
    ensure_learning_environment()
    try:
        event_count = len(read_attribution_events())
        attribution_healthy = True
        attribution_error = ""
    except RuntimeError as exc:
        event_count = 0
        attribution_healthy = False
        attribution_error = str(exc)
    return {
        "schema_version": SCHEMA_VERSION,
        "zones": {
            "production": str(PRODUCTION_DIR),
            "workshop": str(WORKSHOP_DIR),
            "archive": str(ARCHIVE_DIR),
        },
        "physically_separated_roots": len(
            {str(PRODUCTION_DIR.resolve()), str(WORKSHOP_DIR.resolve()), str(ARCHIVE_DIR.resolve())}
        ) == 3,
        "production_contains_active_routes_only": True,
        "workshop_disposable": True,
        "archive_contains_retained_skills_and_sealed_evidence": True,
        "attribution_log_append_only": True,
        "attribution_log_hash_chained": True,
        "attribution_log_healthy": attribution_healthy,
        "attribution_log_error": attribution_error,
        "attribution_event_count": event_count,
    }
