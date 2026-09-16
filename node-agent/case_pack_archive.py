"""Immutable sealed case-pack archive for Jade Genesis 0.1.21.

A case pack is the durable truth behind one sealed verifier dataset. It contains
TRAIN, VALIDATION and SEALED_TEST pairs plus the private commitment material and
is written exactly once under archive/case-packs/. The workshop never receives
this file or its path; it only receives VerifiableTaskLedger.learning_view().

This module is trusted archive plumbing. It intentionally reads the ledger's
private persisted state while holding the ledger lock so the exported pack is an
atomic snapshot of the exact sealed dataset commitment.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
from pathlib import Path
from typing import Any

from learning_environment import ARCHIVE_CASE_PACK_DIR

SCHEMA_VERSION = 1
_MAX_ID_CHARS = 160
_SAFE_ID_RE = re.compile(r"[^A-Za-z0-9._-]+")


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def _safe_component(value: Any, fallback: str) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:_MAX_ID_CHARS]
    text = _SAFE_ID_RE.sub("-", text).strip(".-_")
    return text or fallback


def _pack_digest(pack_without_digest: dict[str, Any]) -> str:
    return hashlib.sha256(
        _canonical_json(pack_without_digest).encode("utf-8")
    ).hexdigest()


def _fsync_directory(path: Path) -> None:
    """Best-effort directory fsync after publishing an immutable pack."""

    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        try:
            os.fsync(fd)
        except OSError:
            pass
    finally:
        os.close(fd)


def archive_sealed_case_pack(
    ledger: Any,
    identity_id: str,
    dataset_id: str,
    *,
    archive_dir: Path = ARCHIVE_CASE_PACK_DIR,
) -> dict[str, Any]:
    """Write or verify one immutable complete case pack for a sealed dataset.

    Publication is crash-safe: the complete pack is fsynced into a private
    unique temp file first, then hard-linked into its immutable final name. A
    crash can therefore leave an orphan temp file, but never a partially written
    final case-pack path. Replays verify exact bytes and never overwrite a pack.
    """

    clean_identity = str(identity_id or "").strip()
    clean_dataset = str(dataset_id or "").strip()
    if not clean_identity:
        raise ValueError("missing_identity_id")
    if not clean_dataset:
        raise ValueError("missing_dataset_id")

    with ledger.lock:
        state = ledger._load()  # trusted archive boundary, never teacher-facing
        ledger._bind_identity(state, clean_identity)
        dataset = state.get("datasets", {}).get(clean_dataset)
        if not isinstance(dataset, dict):
            raise ValueError("dataset_not_found")
        if dataset.get("sealed") is not True:
            raise PermissionError("case_pack_requires_sealed_dataset")
        commitment = str(dataset.get("sealed_set_sha256", "")).strip().lower()
        nonce = str(dataset.get("seal_nonce", "")).strip()
        if len(commitment) != 64 or not nonce:
            raise RuntimeError("sealed_dataset_commitment_incomplete")

        ordered_cases = [
            {
                "case_id": str(case.get("case_id", "")),
                "partition": str(case.get("partition", "")),
                "input": case.get("input"),
                "expected_output": case.get("expected_output"),
                "source": str(case.get("source", "")),
                "created_at": max(0, int(case.get("created_at", 0))),
            }
            for case in dataset.get("cases", {}).values()
            if isinstance(case, dict)
        ]
        ordered_cases.sort(key=lambda item: (item["partition"], item["case_id"]))
        base = {
            "schema_version": SCHEMA_VERSION,
            "identity_id": clean_identity,
            "dataset_id": clean_dataset,
            "task_family": str(dataset.get("task_family", "")),
            "verifier_kind": str(dataset.get("verifier_kind", "")),
            "sealed_at": max(0, int(dataset.get("sealed_at", 0))),
            "sealed_set_sha256": commitment,
            "seal_nonce": nonce,
            "cases": ordered_cases,
        }

    digest = _pack_digest(base)
    pack = {**base, "case_pack_sha256": digest}
    archive_dir.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(archive_dir, 0o700)
    except OSError:
        pass
    filename = (
        f"{_safe_component(clean_dataset, 'dataset')}-"
        f"{commitment}.case-pack.json"
    )
    target = archive_dir / filename
    encoded = (_canonical_json(pack) + "\n").encode("utf-8")

    if target.exists():
        existing = target.read_bytes()
        if existing != encoded:
            raise RuntimeError("case_pack_immutable_conflict")
        try:
            os.chmod(target, 0o400)
        except OSError:
            pass
        return case_pack_public_manifest(target, pack, replayed=True)

    temp = archive_dir / (
        f".{filename}.{os.getpid()}.{secrets.token_hex(8)}.tmp"
    )
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(temp, flags, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=True) as handle:
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.chmod(temp, 0o400)
        except OSError:
            pass

        try:
            os.link(temp, target)
            replayed = False
            _fsync_directory(archive_dir)
        except FileExistsError:
            existing = target.read_bytes()
            if existing != encoded:
                raise RuntimeError("case_pack_immutable_conflict")
            replayed = True
        try:
            os.chmod(target, 0o400)
        except OSError:
            pass
        return case_pack_public_manifest(target, pack, replayed=replayed)
    finally:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass


def verify_case_pack(path: Path) -> dict[str, Any]:
    """Verify immutable file content and return the trusted pack."""

    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError("case_pack_corrupt") from exc
    if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA_VERSION:
        raise RuntimeError("case_pack_schema_invalid")
    digest = str(raw.get("case_pack_sha256", "")).lower()
    base = {key: value for key, value in raw.items() if key != "case_pack_sha256"}
    if len(digest) != 64 or _pack_digest(base) != digest:
        raise RuntimeError("case_pack_hash_mismatch")
    commitment = str(raw.get("sealed_set_sha256", "")).lower()
    if len(commitment) != 64 or not str(raw.get("seal_nonce", "")):
        raise RuntimeError("case_pack_commitment_incomplete")
    cases = raw.get("cases", [])
    if not isinstance(cases, list) or not any(
        isinstance(case, dict) and case.get("partition") == "SEALED_TEST"
        for case in cases
    ):
        raise RuntimeError("case_pack_missing_sealed_partition")
    return raw


def case_pack_public_manifest(
    path: Path,
    pack: dict[str, Any],
    *,
    replayed: bool,
) -> dict[str, Any]:
    cases = pack.get("cases", []) if isinstance(pack.get("cases"), list) else []
    return {
        "dataset_id": pack.get("dataset_id", ""),
        "task_family": pack.get("task_family", ""),
        "sealed_set_sha256": pack.get("sealed_set_sha256", ""),
        "case_pack_sha256": pack.get("case_pack_sha256", ""),
        "case_count": len(cases),
        "train_count": sum(1 for case in cases if case.get("partition") == "TRAIN"),
        "validation_count": sum(
            1 for case in cases if case.get("partition") == "VALIDATION"
        ),
        "sealed_test_count": sum(
            1 for case in cases if case.get("partition") == "SEALED_TEST"
        ),
        "replayed": bool(replayed),
        "immutable": True,
        "path_exposed_to_teacher": False,
        "sealed_test_inputs_exposed": False,
        "sealed_test_answers_exposed": False,
        "seal_nonce_exposed": False,
        # Trusted callers may log only the filename, never hand it to teachers.
        "archive_filename": path.name,
    }
