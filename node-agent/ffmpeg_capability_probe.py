"""Bounded FFmpeg capability probe for Jade Genesis.

This module performs one fixed synthetic transcode to prove that the local
FFmpeg capability can execute and be machine-verified.

It never accepts user file paths, arbitrary FFmpeg arguments, shell commands,
network inputs, or persistent output destinations.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Callable

PROBE_ID = "ffmpeg_synthetic_mpeg4_mp4_v1"
PROVIDER_ID = "ffmpeg-local"
OPERATION = "media_transcode_probe"
WIDTH = 160
HEIGHT = 90
FPS = 10
DURATION_SECONDS = 0.5
MAX_STDERR_CHARS = 1_200


def ffmpeg_probe_status(
    which: Callable[[str], str | None] = shutil.which,
) -> dict:
    ffmpeg = which("ffmpeg")
    ffprobe = which("ffprobe")
    return {
        "schema_version": 1,
        "probe_id": PROBE_ID,
        "provider_id": PROVIDER_ID,
        "operation": OPERATION,
        "ready": bool(ffmpeg and ffprobe),
        "ffmpeg_present": bool(ffmpeg),
        "ffprobe_present": bool(ffprobe),
        "bounded_synthetic_input_only": True,
        "user_file_access": False,
        "arbitrary_arguments_allowed": False,
        "shell_execution": False,
        "network_input_allowed": False,
        "persistent_output": False,
    }


def _default_runner(command: list[str], timeout_seconds: float) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        shell=False,
        timeout=timeout_seconds,
    )


def _parse_request(payload: str) -> dict:
    if len(payload) > 512:
        raise ValueError("ffmpeg_probe_payload_too_large")
    if not payload.strip():
        return {"profile": PROBE_ID}
    try:
        request = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError("invalid_ffmpeg_probe_payload") from exc
    if not isinstance(request, dict):
        raise ValueError("invalid_ffmpeg_probe_payload")

    unknown = set(request) - {"profile"}
    if unknown:
        raise ValueError("ffmpeg_probe_unknown_fields")

    profile = str(request.get("profile", PROBE_ID)).strip()
    if profile != PROBE_ID:
        raise ValueError("unsupported_ffmpeg_probe_profile")

    return {"profile": profile}


def _run_checked(
    command: list[str],
    *,
    timeout_seconds: float,
    runner: Callable[[list[str], float], subprocess.CompletedProcess[str]],
    error_code: str,
) -> subprocess.CompletedProcess[str]:
    try:
        result = runner(command, timeout_seconds)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"{error_code}_timeout") from exc
    except OSError as exc:
        raise RuntimeError(f"{error_code}_launch_failed") from exc

    if int(getattr(result, "returncode", 1)) != 0:
        stderr = str(getattr(result, "stderr", "") or "")[:MAX_STDERR_CHARS]
        suffix = f":{stderr}" if stderr else ""
        raise RuntimeError(f"{error_code}_failed{suffix}")
    return result


def run_ffmpeg_transcode_probe(
    payload: str = "",
    *,
    which: Callable[[str], str | None] = shutil.which,
    runner: Callable[[list[str], float], subprocess.CompletedProcess[str]] = _default_runner,
    clock_ns: Callable[[], int] = time.perf_counter_ns,
) -> tuple[str, int]:
    """Execute one fixed local synthetic transcode and verify the result."""

    request = _parse_request(payload)
    ffmpeg = which("ffmpeg")
    ffprobe = which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise RuntimeError("ffmpeg_probe_runtime_unavailable")

    started_ns = clock_ns()

    with tempfile.TemporaryDirectory(prefix="jade-ffmpeg-probe-") as temp_dir:
        output_path = Path(temp_dir) / "probe.mp4"

        transcode_command = [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-f",
            "lavfi",
            "-i",
            f"testsrc2=size={WIDTH}x{HEIGHT}:rate={FPS}",
            "-t",
            str(DURATION_SECONDS),
            "-an",
            "-threads",
            "1",
            "-c:v",
            "mpeg4",
            "-q:v",
            "5",
            "-y",
            str(output_path),
        ]
        _run_checked(
            transcode_command,
            timeout_seconds=15.0,
            runner=runner,
            error_code="ffmpeg_transcode",
        )

        if not output_path.is_file():
            raise RuntimeError("ffmpeg_probe_missing_output")
        output_bytes = output_path.stat().st_size
        if output_bytes <= 0 or output_bytes > 8 * 1024 * 1024:
            raise RuntimeError("ffmpeg_probe_output_size_invalid")

        probe_command = [
            str(ffprobe),
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=codec_name,width,height,r_frame_rate,duration:format=duration,size",
            "-of",
            "json",
            str(output_path),
        ]
        probe_result = _run_checked(
            probe_command,
            timeout_seconds=5.0,
            runner=runner,
            error_code="ffprobe_verify",
        )

        try:
            metadata = json.loads(str(probe_result.stdout or "{}"))
        except json.JSONDecodeError as exc:
            raise RuntimeError("ffprobe_invalid_json") from exc
        if not isinstance(metadata, dict):
            raise RuntimeError("ffprobe_invalid_json")

        streams = metadata.get("streams", [])
        stream = streams[0] if isinstance(streams, list) and streams else {}
        if not isinstance(stream, dict):
            stream = {}
        format_data = metadata.get("format", {})
        if not isinstance(format_data, dict):
            format_data = {}

        codec = str(stream.get("codec_name", "")).strip().lower()
        width = int(stream.get("width", 0) or 0)
        height = int(stream.get("height", 0) or 0)
        frame_rate = str(stream.get("r_frame_rate", "")).strip()
        raw_duration = stream.get("duration", format_data.get("duration", 0.0))
        try:
            duration_seconds = float(raw_duration)
        except (TypeError, ValueError):
            duration_seconds = 0.0

        verification_passed = (
            codec == "mpeg4"
            and width == WIDTH
            and height == HEIGHT
            and 0.25 <= duration_seconds <= 1.0
        )
        if not verification_passed:
            raise RuntimeError("ffmpeg_probe_verification_failed")

        sha256 = hashlib.sha256(output_path.read_bytes()).hexdigest()

    duration_ms = max(0, (clock_ns() - started_ns) // 1_000_000)
    response = {
        "schema_version": 1,
        "probe_id": PROBE_ID,
        "provider_id": PROVIDER_ID,
        "operation": OPERATION,
        "profile": request["profile"],
        "success": True,
        "verification_passed": True,
        "bounded_synthetic_input_only": True,
        "user_file_access": False,
        "arbitrary_arguments_allowed": False,
        "shell_execution": False,
        "network_input_allowed": False,
        "persistent_output": False,
        "output": {
            "codec": codec,
            "width": width,
            "height": height,
            "frame_rate": frame_rate,
            "duration_seconds": round(duration_seconds, 3),
            "bytes": output_bytes,
            "sha256": sha256,
        },
        "metrics": {
            "duration_ms": int(duration_ms),
        },
    }
    return (
        json.dumps(response, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
        int(duration_ms),
    )
