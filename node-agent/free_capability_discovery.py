"""Dependency-free discovery for Jade Genesis free/local capabilities.

This module is intentionally read-only. It never installs software, starts
processes, purchases credits, or probes non-loopback network targets.
"""

from __future__ import annotations

import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable


FREE_TOOL_SPECS = (
    {
        "id": "ollama-local",
        "display_name": "Ollama",
        "provider_type": "LOCAL_MODEL",
        "operations": (
            "text_generation",
            "reasoning",
            "local_model_inference",
        ),
        "commands": ("ollama",),
    },
    {
        "id": "comfyui-local",
        "display_name": "ComfyUI",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "image_generation",
            "image_workflow",
        ),
        "commands": ("comfyui", "comfy"),
    },
    {
        "id": "whisper-cpp-local",
        "display_name": "whisper.cpp",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "speech_to_text",
            "audio_transcription",
        ),
        "commands": (
            "whisper-cli",
            "whisper-cpp",
            "whisper",
        ),
    },
    {
        "id": "piper-local",
        "display_name": "Piper",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "text_to_speech",
            "voice_synthesis",
        ),
        "commands": ("piper",),
    },
    {
        "id": "blender-local",
        "display_name": "Blender",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "3d_modeling",
            "3d_rendering",
            "3d_animation",
        ),
        "commands": ("blender",),
    },
    {
        "id": "ffmpeg-local",
        "display_name": "FFmpeg",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "media_transcode",
            "media_assembly",
            "audio_video_mux",
        ),
        "commands": ("ffmpeg",),
    },
    {
        "id": "krita-local",
        "display_name": "Krita",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "image_editing",
            "digital_painting",
        ),
        "commands": ("krita",),
    },
    {
        "id": "playwright-local",
        "display_name": "Playwright",
        "provider_type": "LOCAL_TOOL",
        "operations": (
            "browser_automation",
            "web_ui_interaction",
        ),
        "commands": ("playwright",),
    },
)


def _first_executable(
    commands: tuple[str, ...],
    which: Callable[[str], str | None],
) -> tuple[str, str]:
    for command in commands:
        path = which(command)
        if path:
            return command, str(path)
    return "", ""


def _is_loopback_http_url(url: str) -> bool:
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError:
        return False
    if parsed.scheme not in {"http", "https"}:
        return False
    host = (parsed.hostname or "").lower()
    return host in {"127.0.0.1", "localhost", "::1"}


def _default_http_probe(url: str, timeout_seconds: float) -> bool:
    request = urllib.request.Request(
        url,
        method="GET",
        headers={"Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            status = int(getattr(response, "status", 200))
            return 200 <= status < 500
    except (
        urllib.error.URLError,
        TimeoutError,
        OSError,
        ValueError,
    ):
        return False


def discover_free_capabilities(
    *,
    ollama_ready: bool = False,
    ollama_model: str = "",
    comfyui_url: str = "http://127.0.0.1:8188",
    which: Callable[[str], str | None] = shutil.which,
    http_probe: Callable[[str, float], bool] = _default_http_probe,
    captured_at_ms: int | None = None,
) -> dict:
    """Return a bounded, read-only inventory of free/local capabilities.

    Discovery rules are conservative:
    - command-backed tools are detected only from PATH;
    - Ollama may also be marked available from the existing runtime health
      result, so we do not duplicate model/service probing;
    - ComfyUI may be detected through a loopback-only health probe;
    - all entries are LOCAL_FREE and no paid provider is represented here.
    """

    items: list[dict] = []
    comfyui_probe_allowed = _is_loopback_http_url(comfyui_url)
    comfyui_ready = False
    if comfyui_probe_allowed:
        base = comfyui_url.rstrip("/")
        comfyui_ready = bool(http_probe(f"{base}/system_stats", 0.20))

    for spec in FREE_TOOL_SPECS:
        command, executable_path = _first_executable(spec["commands"], which)
        available = bool(executable_path)
        evidence: list[str] = []
        details: dict[str, object] = {}

        if executable_path:
            evidence.append("executable_on_path")
            details["command"] = command
            details["executable_path"] = executable_path

        if spec["id"] == "ollama-local" and ollama_ready:
            available = True
            evidence.append("node_runtime_ollama_ready")
            if ollama_model:
                details["model"] = ollama_model

        if spec["id"] == "comfyui-local" and comfyui_ready:
            available = True
            evidence.append("loopback_service_ready")
            details["service_url"] = comfyui_url.rstrip("/")
        elif spec["id"] == "comfyui-local" and not comfyui_probe_allowed:
            evidence.append("non_loopback_probe_rejected")

        items.append(
            {
                "id": spec["id"],
                "display_name": spec["display_name"],
                "provider_type": spec["provider_type"],
                "cost_class": "LOCAL_FREE",
                "operations": list(spec["operations"]),
                "available": available,
                "requires_network": False,
                "evidence": evidence,
                "details": details,
            }
        )

    available_ids = [
        str(item["id"])
        for item in items
        if bool(item["available"])
    ]

    return {
        "schema_version": 1,
        "policy": "LOCAL_FREE_ONLY",
        "read_only_discovery": True,
        "automatic_installation": False,
        "paid_provider_count": 0,
        "captured_at": (
            int(captured_at_ms)
            if captured_at_ms is not None
            else int(time.time() * 1000)
        ),
        "available_ids": available_ids,
        "items": items,
    }
