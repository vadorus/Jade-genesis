"""Shared in-process path locks and crash-safer atomic file replacement.

JSON stores in the node runtime are frequently reopened through small factory
helpers. A lock owned only by one store instance does not serialize two such
instances that point at the same file. This module provides one re-entrant lock
per canonical path for the lifetime of the process, plus unique temporary files
for atomic replacement so concurrent writers never contend on one fixed `.tmp`.

The lock is deliberately process-local. Production Night Cycle and HTTP request
threads run in the same Node Runtime process; operator one-off maintenance
processes must still avoid concurrent writes to the live runtime.
"""

from __future__ import annotations

import os
import stat
import tempfile
import threading
from pathlib import Path
from typing import Any

_LOCKS_GUARD = threading.Lock()
_LOCKS: dict[str, Any] = {}


def canonical_store_path(path: Path | str) -> str:
    return str(Path(path).expanduser().resolve(strict=False))


def shared_path_lock(path: Path | str) -> Any:
    """Return the process-wide re-entrant lock for one canonical file path."""

    key = canonical_store_path(path)
    with _LOCKS_GUARD:
        lock = _LOCKS.get(key)
        if lock is None:
            lock = threading.RLock()
            _LOCKS[key] = lock
        return lock


def _target_mode(path: Path) -> int:
    try:
        return stat.S_IMODE(path.stat().st_mode)
    except OSError:
        return 0o600


def _fsync_parent(path: Path) -> None:
    """Best-effort directory fsync on platforms that support directory FDs."""

    flags = getattr(os, "O_RDONLY", 0)
    directory_flag = getattr(os, "O_DIRECTORY", 0)
    try:
        fd = os.open(str(path.parent), flags | directory_flag)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def atomic_write_text(path: Path | str, content: str) -> None:
    """Write UTF-8 text through a unique sibling temp and os.replace()."""

    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    mode = _target_mode(target)
    fd, temp_name = tempfile.mkstemp(
        prefix=f".{target.name}.",
        suffix=".tmp",
        dir=str(target.parent),
    )
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.chmod(temp, mode)
        except OSError:
            pass
        os.replace(temp, target)
        _fsync_parent(target)
    except BaseException:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def atomic_write_bytes(path: Path | str, content: bytes) -> None:
    """Write bytes through a unique sibling temp and os.replace()."""

    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    mode = _target_mode(target)
    fd, temp_name = tempfile.mkstemp(
        prefix=f".{target.name}.",
        suffix=".tmp",
        dir=str(target.parent),
    )
    temp = Path(temp_name)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.chmod(temp, mode)
        except OSError:
            pass
        os.replace(temp, target)
        _fsync_parent(target)
    except BaseException:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def atomic_copy_file(source: Path | str, destination: Path | str) -> None:
    atomic_write_bytes(destination, Path(source).read_bytes())
