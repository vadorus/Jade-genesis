#!/usr/bin/env python3
"""Role-aware model selection for Jade Genesis Node Runtime.

This module deliberately keeps model choice separate from Jade's identity.  It
selects an installed Ollama model for a bounded cognitive profile (fast,
general, reasoning, code or critic), but it never downloads models, mutates
model weights, changes privileges, or executes arbitrary commands.
"""

from __future__ import annotations

import json
import re
import time
from typing import Any

POLICY_VERSION = "1"
PROFILES = ("fast", "general", "reasoning", "code", "critic")

_MIN_PARAMETERS_B = {
    "fast": 0.0,
    "general": 6.0,
    "reasoning": 7.0,
    "code": 7.0,
    "critic": 7.0,
}

_PROFILE_HINTS = {
    "fast": ("mini", "small", "3b", "4b"),
    "general": ("qwen3", "gemma3", "llama", "mistral", "phi4"),
    "reasoning": ("deepseek-r1", "qwq", "qwen3", "reason", "thinking"),
    "code": ("coder", "codestral", "codeqwen", "starcoder", "deepseek-coder"),
    "critic": ("deepseek-r1", "qwq", "qwen3", "reason", "thinking"),
}

_PROFILE_OPTIONS = {
    "fast": {"temperature": 0.20, "num_ctx": 4096},
    "general": {"temperature": 0.30, "num_ctx": 8192},
    "reasoning": {"temperature": 0.15, "num_ctx": 12288},
    "code": {"temperature": 0.10, "num_ctx": 12288},
    "critic": {"temperature": 0.05, "num_ctx": 8192},
}


def normalize_profile(value: Any) -> str:
    candidate = str(value or "").strip().lower()
    return candidate if candidate in PROFILES else "general"


def profile_from_operation(operation: Any, requested: Any = None) -> str:
    operation_name = str(operation or "answer").strip().lower()
    if operation_name == "verify":
        return "critic"
    if operation_name == "revise":
        return "reasoning"
    if operation_name == "tool_build":
        return "code"
    if requested is not None and str(requested).strip():
        return normalize_profile(requested)
    return "general"


def profile_options(profile: str) -> dict[str, Any]:
    return dict(_PROFILE_OPTIONS[normalize_profile(profile)])


def _model_name(item: dict[str, Any]) -> str:
    return str(item.get("name") or item.get("model") or "").strip()


def _model_size_bytes(item: dict[str, Any]) -> int:
    try:
        return max(0, int(item.get("size", 0) or 0))
    except (TypeError, ValueError):
        return 0


def _parameter_billions(item: dict[str, Any]) -> float:
    details = item.get("details") if isinstance(item.get("details"), dict) else {}
    raw = str(details.get("parameter_size") or item.get("parameter_size") or "").strip().upper()
    match = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*B", raw)
    if match:
        try:
            return float(match.group(1))
        except ValueError:
            pass

    name = _model_name(item).lower()
    match = re.search(r"(?:^|[-_:])([0-9]+(?:\.[0-9]+)?)b(?:$|[-_:])", name)
    if match:
        try:
            return float(match.group(1))
        except ValueError:
            pass
    return 0.0


def _find_configured(models: list[dict[str, Any]], configured: str) -> dict[str, Any] | None:
    wanted = configured.strip()
    if not wanted:
        return None
    wanted_base = wanted.split(":", 1)[0]
    exact = next((item for item in models if _model_name(item) == wanted), None)
    if exact is not None:
        return exact
    return next(
        (item for item in models if _model_name(item).split(":", 1)[0] == wanted_base),
        None,
    )


def _configured_model(config: dict[str, Any], profile: str) -> str:
    role_key = f"brain_model_{profile}"
    role_value = str(config.get(role_key, "")).strip()
    if role_value:
        return role_value
    if profile == "general":
        return str(config.get("ollama_model", "")).strip()
    return ""


def _score_model(
    item: dict[str, Any],
    profile: str,
    gpu_vram_free_gb: float,
) -> float:
    name = _model_name(item).lower()
    size_bytes = _model_size_bytes(item)
    size_gb = size_bytes / (1024 ** 3) if size_bytes > 0 else 0.0
    params_b = _parameter_billions(item)
    minimum = _MIN_PARAMETERS_B[profile]
    score = 0.0

    for hint in _PROFILE_HINTS[profile]:
        if hint in name:
            score += 180.0

    if profile == "fast":
        if size_gb > 0:
            score += max(0.0, 180.0 - size_gb * 18.0)
        if params_b > 0:
            score += max(0.0, 80.0 - params_b * 6.0)
    else:
        if params_b >= minimum:
            score += 300.0 + min(params_b, 32.0) * 7.0
        elif params_b > 0:
            # Small models may remain a degraded fallback, but they are never
            # preferred as Jade's general/reasoning/code/critic brain.
            score -= 420.0 + (minimum - params_b) * 25.0
        elif size_gb >= 4.0:
            score += 120.0
        else:
            score -= 220.0

        if profile in {"reasoning", "critic"} and any(
            hint in name for hint in ("deepseek-r1", "qwq", "reason", "thinking")
        ):
            score += 260.0
        if profile == "code" and any(
            hint in name for hint in ("coder", "codestral", "starcoder", "codeqwen")
        ):
            score += 320.0

    if gpu_vram_free_gb > 0 and size_gb > 0:
        if size_gb <= gpu_vram_free_gb * 0.92:
            score += 130.0
        elif size_gb <= gpu_vram_free_gb * 1.35:
            score += 25.0
        else:
            score -= min(240.0, (size_gb - gpu_vram_free_gb) * 22.0)

    # Stable tie breaker: for capable profiles prefer the larger model, while
    # FAST prefers the smaller one.
    score += (-size_gb if profile == "fast" else size_gb)
    return score


def select_profile_model(
    config: dict[str, Any],
    models: list[dict[str, Any]],
    profile: str,
    gpu_vram_free_gb: float = 0.0,
) -> dict[str, Any] | None:
    profile = normalize_profile(profile)
    usable = [item for item in models if isinstance(item, dict) and _model_name(item)]
    if not usable:
        return None

    configured = _configured_model(config, profile)
    if configured:
        explicit = _find_configured(usable, configured)
        if explicit is None:
            return None
        chosen = explicit
        reason = f"configured:{configured}"
    else:
        chosen = max(
            usable,
            key=lambda item: (_score_model(item, profile, gpu_vram_free_gb), _model_name(item)),
        )
        reason = "automatic_role_score"

    params_b = _parameter_billions(chosen)
    size_bytes = _model_size_bytes(chosen)
    minimum = _MIN_PARAMETERS_B[profile]
    degraded = profile != "fast" and (
        (params_b > 0 and params_b < minimum)
        or (params_b <= 0 and 0 < size_bytes < 4 * 1024 ** 3)
    )
    return {
        "profile": profile,
        "model": _model_name(chosen),
        "parameter_billions": round(params_b, 2),
        "model_size_gb": round(size_bytes / (1024 ** 3), 2) if size_bytes else 0.0,
        "degraded": degraded,
        "reason": reason,
    }


def _profile_instruction(profile: str, degraded: bool) -> str:
    profile = normalize_profile(profile)
    instructions = {
        "fast": "Profil FAST : privilégie une réponse courte, directe et peu coûteuse.",
        "general": "Profil GENERAL : équilibre précision, contexte, utilité et coût.",
        "reasoning": "Profil REASONING : traite soigneusement les contraintes et vérifie les conclusions sans révéler de chaîne de pensée privée.",
        "code": "Profil CODE : privilégie exactitude technique, interfaces stables, tests et code complet lorsqu'il est demandé.",
        "critic": "Profil CRITIC : cherche les erreurs, incohérences et risques puis rends uniquement le verdict structuré demandé.",
    }
    suffix = instructions[profile]
    if degraded:
        suffix += " Le modèle disponible est sous le niveau de capacité préféré : reste prudent et signale l'incertitude utile."
    return suffix


def run_brain_chat_profiled(payload: str, config: dict[str, Any], core: Any) -> tuple[str, int]:
    started = time.perf_counter_ns()
    try:
        context = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError("invalid_brain_payload") from exc
    if not isinstance(context, dict):
        raise ValueError("invalid_brain_payload")

    requested = context.get("brain_profile")
    profile = profile_from_operation(context.get("operation", "answer"), requested)

    try:
        models = core.ollama_models(config, timeout=1.2)
    except Exception as exc:
        raise RuntimeError("Ollama local n'est pas joignable.") from exc

    gpu = core.gpu_telemetry()
    free_vram = float(gpu.get("gpu_vram_free_gb", 0.0) or 0.0)
    selection = select_profile_model(config, models, profile, free_vram)
    if selection is None:
        configured = _configured_model(config, profile)
        if configured:
            raise RuntimeError(
                f"Le modèle configuré pour le profil {profile.upper()} ('{configured}') n'est pas installé."
            )
        raise RuntimeError(f"Aucun modèle Ollama utilisable pour le profil {profile.upper()}.")

    model = str(selection["model"])
    base = core.normalize_ollama_url(str(config.get("ollama_url", core.DEFAULT_OLLAMA_URL)))
    options = profile_options(profile)
    system_prompt = core._brain_system_prompt(context) + " " + _profile_instruction(
        profile,
        bool(selection["degraded"]),
    )
    request_payload = {
        "model": model,
        "stream": False,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": core._brain_user_prompt(context)},
        ],
        "options": options,
    }
    response = core._json_request(
        f"{base}/api/chat",
        method="POST",
        payload=request_payload,
        timeout=180.0 if profile in {"reasoning", "code"} else 150.0,
    )
    message = response.get("message", {})
    if not isinstance(message, dict):
        raise RuntimeError("Ollama n'a pas renvoyé de message.")
    text = str(message.get("content", "")).strip()
    if not text:
        raise RuntimeError("Ollama a renvoyé une réponse vide.")

    duration_ms = int((time.perf_counter_ns() - started) // 1_000_000)
    eval_count = max(0, core.safe_int(response.get("eval_count", 0), 0))
    eval_duration_ns = max(0, core.safe_int(response.get("eval_duration", 0), 0))
    prompt_eval_count = max(0, core.safe_int(response.get("prompt_eval_count", 0), 0))
    prompt_eval_duration_ns = max(0, core.safe_int(response.get("prompt_eval_duration", 0), 0))
    load_duration_ns = max(0, core.safe_int(response.get("load_duration", 0), 0))
    total_duration_ns = max(0, core.safe_int(response.get("total_duration", 0), 0))
    tokens_per_second = (
        eval_count / (eval_duration_ns / 1_000_000_000.0)
        if eval_count > 0 and eval_duration_ns > 0
        else 0.0
    )
    with core._BRAIN_METRICS_LOCK:
        core._BRAIN_METRICS.update(
            {
                "model": model,
                "tokens_per_second": round(tokens_per_second, 2),
                "duration_ms": duration_ms,
                "load_duration_ms": int(load_duration_ns // 1_000_000),
                "measured_at": int(time.time() * 1000),
            }
        )

    result = {
        "text": text,
        "backend": "ollama_profiled",
        "model": model,
        "brain_profile": profile,
        "brain_profile_policy": POLICY_VERSION,
        "selection_reason": selection["reason"],
        "degraded": bool(selection["degraded"]),
        "parameter_billions": selection["parameter_billions"],
        "model_size_gb": selection["model_size_gb"],
        "local": True,
        "operation": str(context.get("operation", "answer")),
        "eval_count": eval_count,
        "eval_duration_ms": eval_duration_ns // 1_000_000,
        "prompt_eval_count": prompt_eval_count,
        "prompt_eval_duration_ms": prompt_eval_duration_ns // 1_000_000,
        "tokens_per_second": round(tokens_per_second, 2),
        "load_duration_ms": int(load_duration_ns // 1_000_000),
        "ollama_total_duration_ms": total_duration_ns // 1_000_000,
    }
    return json.dumps(result, ensure_ascii=False, separators=(",", ":")), duration_ms


def brain_profiles_status(config: dict[str, Any], core: Any) -> dict[str, Any]:
    status: dict[str, Any] = {
        "policy_version": POLICY_VERSION,
        "profiles": {},
        "automatic_download": False,
        "automatic_model_weight_mutation": False,
        "automatic_privilege_escalation": False,
    }
    try:
        models = core.ollama_models(config, timeout=0.8)
        gpu = core.gpu_telemetry()
        free_vram = float(gpu.get("gpu_vram_free_gb", 0.0) or 0.0)
        for profile in PROFILES:
            selection = select_profile_model(config, models, profile, free_vram)
            status["profiles"][profile] = selection or {
                "profile": profile,
                "model": "",
                "degraded": True,
                "reason": "no_model",
            }
        status["ready"] = bool(models)
        status["model_count"] = len(models)
        status["error"] = ""
    except Exception as exc:
        status["ready"] = False
        status["model_count"] = 0
        status["error"] = str(exc)[:160]
    return status
