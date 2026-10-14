"""Read-only capture of preapproved public technical documentation for Jade Genesis.

SECURITY / TRUTH:
- Only exact pinned HTTPS documentation URLs; no arbitrary user-supplied URL.
- No redirects, proxies, cookies, credentials, remote commands or execution of HTML.
- Small bounded download with timeout; persist only metadata / raw-body SHA-256.
- A captured source is NOT an inspected source, a verified fact, or a passed test.
- Explicit manual invocation; never auto-deployed to the VPS or Pixel.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

SCHEMA = "jade.official_source_capture.v1"
MAX_BYTES = 1024 * 1024
TIMEOUT_SECONDS = 10
MAX_RECORDS = 3

@dataclass(frozen=True)
class ApprovedSource:
    key: str
    title: str
    url: str

APPROVED: dict[str, ApprovedSource] = {
    "roblox_export": ApprovedSource(
        "roblox_export", "Roblox Creator Hub: export settings",
        "https://create.roblox.com/docs/art/modeling/export-requirements"
    ),
    "roblox_blender": ApprovedSource(
        "roblox_blender", "Roblox Creator Hub: Blender integration",
        "https://create.roblox.com/docs/art/blender"
    ),
    "blender_fbx": ApprovedSource(
        "blender_fbx", "Blender Manual: FBX import/export",
        "https://docs.blender.org/manual/en/latest/files/import_export/fbx.html"
    ),
}
REQUIRED_MIME = ("text/html", "text/plain")


class CaptureFailure(RuntimeError):
    pass


class _RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise CaptureFailure("HTTP redirects are disabled (no URL follow)")


def validate_source(source: ApprovedSource) -> None:
    approved = APPROVED.get(source.key)
    if approved != source:
        raise CaptureFailure("Source not in immutable approved list")
    u = urlsplit(source.url)
    if (u.scheme != "https" or u.username or u.password
        or u.port is not None or not u.hostname or u.query or u.fragment
        or u.path.startswith("//")):
        raise CaptureFailure("Invalid HTTPS source URL")
    if u.hostname not in {"create.roblox.com", "docs.blender.org"}:
        raise CaptureFailure("Unapproved documentation host")


def _open_only_official(req: urllib.request.Request, timeout: float):
    opener = urllib.request.build_opener(
        _RejectRedirects(),
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(None),
    )
    return opener.open(req, timeout=timeout)


def fetch_source_with_body(
    source: ApprovedSource,
    *,
    opener: Callable[[urllib.request.Request, float], Any] = _open_only_official,
    now_ms: int | None = None
) -> tuple[dict[str, Any], bytes]:
    validate_source(source)
    request = urllib.request.Request(
        source.url,
        headers={
            "User-Agent": "JadeGenesisOfficialSourceCapture/0.1 (read-only)",
            "Accept": "text/html, text/plain",
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
        },
        method="GET",
    )
    try:
        with opener(request, TIMEOUT_SECONDS) as response:
            status = int(getattr(response, "status", 0))
            if status != 200:
                raise CaptureFailure(f"Expected HTTP 200, got {status}")
            final_url = response.geturl()
            if final_url != source.url:
                raise CaptureFailure("Final URL differs from pinned approved URL")
            mime = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if mime not in REQUIRED_MIME:
                raise CaptureFailure(f"Unexpected MIME type: {mime}")
            encoding = response.headers.get("Content-Encoding", "identity").lower()
            if encoding not in ("identity", ""):
                raise CaptureFailure("Encoded response refused; digest must cover raw HTML")
            raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise CaptureFailure("Response exceeds hard byte limit")
            if len(raw) < 50:
                raise CaptureFailure("Source response is implausibly short")
            sha = hashlib.sha256(raw).hexdigest()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise CaptureFailure(f"Cannot retrieve approved HTTPS source: {type(exc).__name__}") from exc

    return {
        "source_key": source.key,
        "source_title": source.title,
        "source_url": source.url,
        "source_id": source.key,
        "retrieved_at_ms": int(now_ms if now_ms is not None else time.time() * 1000),
        "sha256": sha,
        "bytes": len(raw),
        "mime": mime,
        "http_status": 200,
        "inspected": False,
        "assessment": "UNASSESSED",
        "experiment_performed": False,
    }, raw


def capture_one(
    source: ApprovedSource,
    *,
    opener: Callable[[urllib.request.Request, float], Any] = _open_only_official,
    now_ms: int | None = None
) -> dict[str, Any]:
    metadata, _raw = fetch_source_with_body(
        source, opener=opener, now_ms=now_ms
    )
    return metadata


def build_capture_manifest(
    keys: list[str],
    *,
    opener: Callable[[urllib.request.Request, float], Any] = _open_only_official,
    now_ms: int | None = None
) -> dict[str, Any]:
    unique = list(dict.fromkeys(keys))
    if not unique or len(unique) > MAX_RECORDS:
        raise ValueError("Choose one to three approved source keys")
    if any(k not in APPROVED for k in unique):
        raise ValueError("Unknown source key")
    captures = []
    failures = []
    for key in unique:
        try:
            captures.append(capture_one(APPROVED[key], opener=opener, now_ms=now_ms))
        except CaptureFailure as exc:
            failures.append({"source_key": key, "error": str(exc)[:180]})
    return {
        "schema": SCHEMA,
        "created_at_ms": int(now_ms if now_ms is not None else time.time() * 1000),
        "records": captures,
        "errors": failures,
        "automatic_verification": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture hashes from preapproved public docs")
    parser.add_argument("--sources", nargs="+", choices=sorted(APPROVED), default=list(APPROVED))
    parser.add_argument("--out", type=Path, required=True, help="Explicit local output path")
    args = parser.parse_args()
    manifest = build_capture_manifest(args.sources)
    target = args.out.expanduser().resolve()
    if target.exists():
        raise SystemExit("Refusing to overwrite existing capture manifest")
    if not target.parent.is_dir():
        raise SystemExit("Output directory does not exist")
    target.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8"
    )
    print(json.dumps({
        "path": str(target),
        "captured": len(manifest["records"]),
        "errors": manifest["errors"],
        "automatic_verification": False
    }, ensure_ascii=False))
    return 0 if not manifest["errors"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
