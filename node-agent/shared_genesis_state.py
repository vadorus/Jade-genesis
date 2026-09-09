"""Durable operational state replica for Jade Genesis Node Runtime.

This module is deliberately dependency-free. It stores an append-only bounded
event stream plus a compact latest-entity snapshot. The VPS is a durable replica,
not the owner of Jade's identity: every sync is scoped to an identity id supplied
by the trusted client and an already-bound store rejects another identity.
"""

from __future__ import annotations

import json
import os
import threading
import time
import uuid
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MAX_EVENTS = 2_000
MAX_ENTITIES = 500
MAX_SYNC_EVENTS = 200
MAX_EVENT_PAYLOAD_CHARS = 64_000
MAX_ID_CHARS = 160
CONFIG_DIR = Path(
    os.environ.get(
        "JADE_GENESIS_CONFIG_DIR",
        str(Path.home() / ".jade-genesis"),
    )
)
STATE_PATH = CONFIG_DIR / "genesis-state.json"


def _now_ms() -> int:
    return int(time.time() * 1000)


def _clean_text(value: Any, name: str, max_chars: int = MAX_ID_CHARS) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError(f"missing_{name}")
    if len(text) > max_chars:
        raise ValueError(f"{name}_too_large")
    return text


def _safe_int(value: Any, fallback: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


class SharedGenesisStateStore:
    def __init__(self, path: Path = STATE_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    def _empty(self) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "events": [],
            "entities": {},
            "updated_at": 0,
        }

    def _load_file(self, path: Path) -> dict[str, Any] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict):
            return None
        if _safe_int(raw.get("schema_version"), 0) != SCHEMA_VERSION:
            return None
        if not isinstance(raw.get("events", []), list):
            return None
        if not isinstance(raw.get("entities", {}), dict):
            return None
        return raw

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return self._empty()
        primary = self._load_file(self.path)
        if primary is not None:
            return primary
        backup = self._load_file(self.backup_path)
        if backup is not None:
            # Restore the valid backup before a later save can rotate the broken
            # primary over it.
            try:
                self.path.write_bytes(self.backup_path.read_bytes())
            except OSError:
                pass
            return backup
        raise RuntimeError("shared_state_corrupt")

    def _save(self, state: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        encoded = json.dumps(
            state,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        temp = self.path.with_suffix(self.path.suffix + ".tmp")
        temp.write_text(encoded, encoding="utf-8")
        if self.path.exists() and self._load_file(self.path) is not None:
            try:
                self.backup_path.write_bytes(self.path.read_bytes())
            except OSError:
                pass
        temp.replace(self.path)

    def _normalize_event(self, raw: Any) -> dict[str, Any]:
        if not isinstance(raw, dict):
            raise ValueError("invalid_shared_state_event")
        event_id = _clean_text(raw.get("event_id"), "event_id")
        origin_node = _clean_text(raw.get("origin_node"), "origin_node")
        kind = _clean_text(raw.get("kind"), "kind", 80)
        entity_id = _clean_text(raw.get("entity_id"), "entity_id")
        payload = str(raw.get("payload", ""))
        if len(payload) > MAX_EVENT_PAYLOAD_CHARS:
            raise ValueError("shared_state_event_payload_too_large")
        created_at = max(0, _safe_int(raw.get("created_at"), 0))
        return {
            "event_id": event_id,
            "origin_node": origin_node,
            "kind": kind,
            "entity_id": entity_id,
            "payload": payload,
            "created_at": created_at,
        }

    @staticmethod
    def _entity_key(event: dict[str, Any]) -> str:
        return f"{event['kind']}:{event['entity_id']}"

    @staticmethod
    def _event_order(event: dict[str, Any]) -> tuple[int, str]:
        return (
            _safe_int(event.get("created_at"), 0),
            str(event.get("event_id", "")),
        )

    def sync(self, request: dict[str, Any]) -> dict[str, Any]:
        if _safe_int(request.get("schema_version"), 0) != SCHEMA_VERSION:
            raise ValueError("incompatible_shared_state_schema")
        identity_id = _clean_text(request.get("identity_id"), "identity_id")
        replica_id = _clean_text(request.get("replica_id"), "replica_id")
        known_revision = max(0, _safe_int(request.get("known_revision"), 0))
        incoming = request.get("events", [])
        if not isinstance(incoming, list):
            raise ValueError("invalid_shared_state_events")
        if len(incoming) > MAX_SYNC_EVENTS:
            raise ValueError("too_many_shared_state_events")

        normalized = [self._normalize_event(item) for item in incoming]
        for event in normalized:
            if event["origin_node"] != replica_id:
                raise ValueError("shared_state_origin_mismatch")

        with self.lock:
            state = self._load()
            bound_identity = str(state.get("identity_id", "")).strip()
            if bound_identity and bound_identity != identity_id:
                raise ValueError("shared_state_identity_mismatch")
            changed = not bound_identity
            if not bound_identity:
                state["identity_id"] = identity_id

            events = [item for item in state.get("events", []) if isinstance(item, dict)]
            existing_ids = {
                str(item.get("event_id", ""))
                for item in events
                if str(item.get("event_id", ""))
            }
            entities = {
                str(key): value
                for key, value in state.get("entities", {}).items()
                if isinstance(value, dict)
            }
            revision = max(0, _safe_int(state.get("revision"), 0))
            ack_ids: list[str] = []

            for event in normalized:
                event_id = event["event_id"]
                ack_ids.append(event_id)
                if event_id in existing_ids:
                    continue
                revision += 1
                canonical = dict(event)
                canonical["server_revision"] = revision
                events.append(canonical)
                existing_ids.add(event_id)
                key = self._entity_key(canonical)
                previous = entities.get(key)
                if previous is None or self._event_order(canonical) >= self._event_order(previous):
                    entities[key] = canonical
                changed = True

            events.sort(key=lambda item: _safe_int(item.get("server_revision"), 0))
            if len(events) > MAX_EVENTS:
                events = events[-MAX_EVENTS:]
                changed = True

            if len(entities) > MAX_ENTITIES:
                newest_entities = sorted(
                    entities.items(),
                    key=lambda pair: self._event_order(pair[1]),
                )[-MAX_ENTITIES:]
                entities = dict(newest_entities)
                changed = True

            state["revision"] = revision
            state["events"] = events
            state["entities"] = entities
            if changed:
                state["updated_at"] = _now_ms()
                self._save(state)

            first_revision = (
                _safe_int(events[0].get("server_revision"), revision + 1)
                if events
                else revision + 1
            )
            reset_required = (
                known_revision > revision
                or known_revision < max(0, first_revision - 1)
            )
            available = [] if reset_required else [
                dict(item)
                for item in events
                if _safe_int(item.get("server_revision"), 0) > known_revision
            ]
            outgoing = available[:MAX_SYNC_EVENTS]
            has_more = len(available) > len(outgoing)
            snapshot = (
                sorted(
                    (dict(value) for value in entities.values()),
                    key=lambda item: (
                        str(item.get("kind", "")),
                        str(item.get("entity_id", "")),
                    ),
                )
                if reset_required
                else []
            )
            response_revision = revision
            if outgoing and has_more:
                response_revision = _safe_int(
                    outgoing[-1].get("server_revision"),
                    known_revision,
                )

            return {
                "schema_version": SCHEMA_VERSION,
                "identity_id": identity_id,
                "replica_id": replica_id,
                "server_revision": response_revision,
                "server_head_revision": revision,
                "ack_event_ids": ack_ids,
                "events": outgoing,
                "has_more": has_more,
                "reset_required": reset_required,
                "snapshot": snapshot,
                "server_updated_at": _safe_int(state.get("updated_at"), 0),
            }

    def status(self) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "revision": max(0, _safe_int(state.get("revision"), 0)),
                "event_count": len(state.get("events", [])),
                "entity_count": len(state.get("entities", {})),
                "updated_at": max(0, _safe_int(state.get("updated_at"), 0)),
            }

    def supervision_view(self) -> dict[str, Any]:
        """Return a detached, bounded view for trusted local maintenance.

        This is intentionally an in-process API: it is not exposed as an HTTP
        endpoint and contains no pairing secret.
        """
        with self.lock:
            state = self._load()
            entities = [
                dict(value)
                for value in state.get("entities", {}).values()
                if isinstance(value, dict)
            ]
            entities.sort(
                key=lambda item: (
                    str(item.get("kind", "")),
                    str(item.get("entity_id", "")),
                )
            )
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_id": str(state.get("identity_id", "")).strip(),
                "revision": max(0, _safe_int(state.get("revision"), 0)),
                "event_count": len(state.get("events", [])),
                "entities": entities[:MAX_ENTITIES],
                "updated_at": max(0, _safe_int(state.get("updated_at"), 0)),
            }

    def append_replica_event(
        self,
        identity_id: str,
        replica_id: str,
        kind: str,
        entity_id: str,
        payload: str,
        created_at: int | None = None,
    ) -> dict[str, Any]:
        """Append one allow-listed local-replica event through normal sync rules."""
        clean_kind = _clean_text(kind, "kind", 80)
        if clean_kind not in {
            "vps_night_cycle_report",
            "vps_maintenance_snapshot",
        }:
            raise ValueError("unsupported_replica_event_kind")
        clean_identity = _clean_text(identity_id, "identity_id")
        clean_replica = _clean_text(replica_id, "replica_id")
        clean_entity = _clean_text(entity_id, "entity_id")
        clean_payload = str(payload)
        if len(clean_payload) > MAX_EVENT_PAYLOAD_CHARS:
            raise ValueError("shared_state_event_payload_too_large")
        view = self.supervision_view()
        if not view["identity_id"]:
            raise ValueError("shared_state_identity_unbound")
        if view["identity_id"] != clean_identity:
            raise ValueError("shared_state_identity_mismatch")
        timestamp = max(0, _safe_int(created_at, _now_ms()))
        event = {
            "event_id": f"state-{uuid.uuid4()}",
            "origin_node": clean_replica,
            "kind": clean_kind,
            "entity_id": clean_entity,
            "payload": clean_payload,
            "created_at": timestamp,
        }
        return self.sync({
            "schema_version": SCHEMA_VERSION,
            "identity_id": clean_identity,
            "replica_id": clean_replica,
            "known_revision": view["revision"],
            "events": [event],
        })


_GLOBAL_STORE = SharedGenesisStateStore()


def run_shared_state_sync(
    payload: str,
    config: dict[str, Any],
) -> tuple[str, int]:
    if str(config.get("node_kind", "")).upper() != "VPS":
        raise ValueError("shared_state_requires_vps")
    started = time.perf_counter_ns()
    try:
        request = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError("invalid_shared_state_payload") from exc
    if not isinstance(request, dict):
        raise ValueError("invalid_shared_state_payload")
    response = _GLOBAL_STORE.sync(request)
    duration_ms = (time.perf_counter_ns() - started) // 1_000_000
    return (
        json.dumps(response, ensure_ascii=False, separators=(",", ":")),
        int(duration_ms),
    )


def shared_state_status() -> dict[str, Any]:
    return _GLOBAL_STORE.status()
