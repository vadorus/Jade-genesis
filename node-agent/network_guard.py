"""Python socket/DNS detection guard; NOT a network namespace or sandbox.

Install only in a dedicated spawned child or an otherwise single-threaded
process: monkeypatching socket is necessarily process-global.
"""
from __future__ import annotations

from contextlib import contextmanager
import socket
import threading
from typing import Callable, Iterator


class NetworkGuardViolation(RuntimeError):
    def __init__(self, operation: str):
        # Do not copy hostnames, URLs, tokens or hidden test content to logs.
        super().__init__("network_guard_violation")
        self.operation = operation


@contextmanager
def no_python_network(
    *, on_violation: Callable[[NetworkGuardViolation], None] | None = None,
    require_single_thread: bool = True,
) -> Iterator[None]:
    if require_single_thread and threading.active_count() != 1:
        raise RuntimeError("network_guard_requires_single_thread_or_spawned_child")
    functions = ("socket", "create_connection", "getaddrinfo",
                 "gethostbyname", "gethostbyname_ex", "getnameinfo")
    originals = {name: getattr(socket, name) for name in functions}

    def blocked(name: str):
        def reject(*_args, **_kwargs):
            exc = NetworkGuardViolation(name)
            if on_violation:
                on_violation(exc)
            raise exc
        return reject

    try:
        for name in functions:
            setattr(socket, name, blocked(name))
        yield
    finally:
        for name, original in originals.items():
            setattr(socket, name, original)
