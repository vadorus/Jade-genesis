"""Bounded Ollama teacher adapter for Jade Genesis 0.1.21.

The teacher is deliberately weaker than the verifier. It receives only the
JADE_SKILL_TEACHER_REQUEST_V1 visible learning request and may return only four
proposal fields: skill_id, description, domain and body. Provenance, verifier
policy, sealed commitment, dependencies and activation are owned by Jade's
SkillSynthesisLoop and cannot be supplied by the model.
"""

from __future__ import annotations

import json
from typing import Any

from brain_profiles import profile_options, select_profile_model

_ALLOWED_RESPONSE_FIELDS = frozenset({"skill_id", "description", "domain", "body"})


def _extract_json_object(text: str) -> dict[str, Any]:
    candidate = str(text or "").strip()
    if candidate.startswith("```"):
        lines = candidate.splitlines()
        if lines and lines[0].lstrip().startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        candidate = "\n".join(lines).strip()
    start = candidate.find("{")
    end = candidate.rfind("}")
    if start < 0 or end < start:
        raise ValueError("teacher_response_json_object_required")
    try:
        value = json.loads(candidate[start : end + 1])
    except json.JSONDecodeError as exc:
        raise ValueError("teacher_response_invalid_json") from exc
    if not isinstance(value, dict):
        raise ValueError("teacher_response_object_required")
    extra = set(value) - _ALLOWED_RESPONSE_FIELDS
    if extra:
        raise ValueError("teacher_response_forbidden_field")
    if not isinstance(value.get("body"), dict):
        raise ValueError("teacher_response_body_required")
    return value


class OllamaSkillTeacher:
    """Callable teacher using an installed Ollama CODE-profile model."""

    def __init__(self, config: dict[str, Any], core: Any):
        self.config = config
        self.core = core
        self.last_model = ""
        self.call_count = 0

    def __call__(self, request: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(request, dict) or request.get("request_kind") != "JADE_SKILL_TEACHER_REQUEST_V1":
            raise ValueError("teacher_request_kind_invalid")
        if request.get("sealed_test_inputs_exposed") is not False:
            raise PermissionError("teacher_sealed_inputs_must_remain_hidden")
        if request.get("sealed_test_answers_exposed") is not False:
            raise PermissionError("teacher_sealed_answers_must_remain_hidden")
        if request.get("seal_nonce_exposed") is not False:
            raise PermissionError("teacher_seal_nonce_must_remain_hidden")

        models = self.core.ollama_models(self.config, timeout=1.2)
        gpu = self.core.gpu_telemetry()
        free_vram = float(gpu.get("gpu_vram_free_gb", 0.0) or 0.0)
        selection = select_profile_model(self.config, models, "code", free_vram)
        if selection is None:
            raise RuntimeError("teacher_code_model_unavailable")

        model = str(selection["model"])
        base = self.core.normalize_ollama_url(
            str(self.config.get("ollama_url", self.core.DEFAULT_OLLAMA_URL))
        )
        system = (
            "Tu es un professeur de procédures Jade Genesis. Réponds avec UN objet JSON brut, "
            "sans markdown et sans texte autour. Champs autorisés uniquement: skill_id, "
            "description, domain, body. body doit être un AST JADE_PROCEDURE_DSL_V1 utilisant "
            "uniquement allowed_ops. Tu ne contrôles jamais provenance, dependencies, "
            "evaluation_policy, sealed_set_sha256, activation ou verdict. Utilise seulement les "
            "cas TRAIN/VALIDATION visibles. Ne devine jamais les cas SEALED_TEST."
        )
        response = self.core._json_request(
            f"{base}/api/chat",
            method="POST",
            payload={
                "model": model,
                "stream": False,
                "messages": [
                    {"role": "system", "content": system},
                    {
                        "role": "user",
                        "content": json.dumps(
                            request,
                            ensure_ascii=False,
                            separators=(",", ":"),
                            sort_keys=True,
                        ),
                    },
                ],
                "options": profile_options("code"),
                "format": "json",
            },
            timeout=180.0,
        )
        message = response.get("message", {})
        if not isinstance(message, dict):
            raise RuntimeError("teacher_ollama_message_missing")
        proposal = _extract_json_object(str(message.get("content", "")))
        self.call_count += 1
        self.last_model = model
        return proposal

    def status(self) -> dict[str, Any]:
        return {
            "teacher_kind": "ollama_code_profile",
            "proposal_only": True,
            "call_count": self.call_count,
            "last_model": self.last_model,
            "controls_provenance": False,
            "controls_verifier": False,
            "controls_sealed_partition": False,
            "controls_activation": False,
            "response_fields": sorted(_ALLOWED_RESPONSE_FIELDS),
        }
