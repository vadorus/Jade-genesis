"""Bounded research, outcome consolidation, hypothesis and experiment planning.

The VPS Night Cycle consumes only synchronized aggregate state. This module never
copies raw conversation text, edits JadeConfig, promotes an Evolution candidate,
rewrites production code, executes shell commands or mutates Pixel memory.
Public research is optional, dependency-free and restricted to one hard-coded
HTTPS provider with strict size/time bounds. Explicit user outcomes are treated
as personal operational evidence, not as externally verified universal facts.
"""

from __future__ import annotations

import hashlib
import json
import time
import urllib.parse
import urllib.request
from typing import Any, Protocol

SCHEMA_VERSION = 1
MAX_RESEARCH_QUESTIONS = 3
MAX_RESEARCH_EVIDENCE = 6
MAX_HYPOTHESES = 4
MAX_EXPERIMENTS = 4
MAX_IMPROVEMENT_CANDIDATES = 4
MAX_RUNTIME_GROUPS = 20
MAX_QUERY_CHARS = 240
MAX_TEXT_CHARS = 500
MAX_PUBLIC_RESPONSE_BYTES = 512 * 1024
PUBLIC_RESEARCH_TIMEOUT_SECONDS = 4.0
MIN_RUNTIME_SAMPLES = 12
MIN_GROUP_SAMPLES = 4
MIN_RESEARCH_CONFIDENCE = 0.75
LOW_SUCCESS_RATE = 0.90
HIGH_FALLBACK_RATE = 0.15
LATENCY_OUTLIER_RATIO = 1.50
THROUGHPUT_OUTLIER_RATIO = 0.65

# Outcome Quality mirrors the immutable Android safety thresholds. Night learning
# may interpret these aggregate values, but it cannot lower the minimum evidence
# required by Runtime Eval or activate a routing change by itself.
MIN_OUTCOME_SAMPLES = 3
STRONG_OUTCOME_SAMPLES = 8
MIN_OUTCOME_COMPARISON_SAMPLES = 5
LOW_OUTCOME_QUALITY = -0.25
MIN_OUTCOME_QUALITY_GAP = 0.35
CORRECTION_PRESSURE_RATE = 0.34


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _safe_int(value: Any, fallback: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def _safe_float(value: Any, fallback: float = 0.0) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return fallback
    if parsed != parsed or parsed in (float("inf"), float("-inf")):
        return fallback
    return parsed


def _clean(value: Any, limit: int = MAX_TEXT_CHARS) -> str:
    return " ".join(str(value or "").replace("\x00", " ").split())[:limit]


def _payload(event: dict[str, Any] | None) -> dict[str, Any]:
    if not event:
        return {}
    try:
        parsed = json.loads(str(event.get("payload", "")))
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _latest_entity(view: dict[str, Any], kind: str) -> dict[str, Any] | None:
    candidates = [
        item
        for item in view.get("entities", [])
        if isinstance(item, dict) and item.get("kind") == kind
    ]
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda item: (
            _safe_int(item.get("created_at"), 0),
            str(item.get("event_id", "")),
        ),
    )


def _stable_id(prefix: str, *parts: Any) -> str:
    raw = "|".join(_clean(part, 400) for part in parts)
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}-{digest}"


class ResearchProvider(Protocol):
    def search(self, query: str) -> list[dict[str, Any]]:
        ...


class DuckDuckGoResearchProvider:
    """Very small public-research adapter with a fixed HTTPS destination."""

    HOST = "api.duckduckgo.com"
    ENDPOINT = f"https://{HOST}/"

    def search(self, query: str) -> list[dict[str, Any]]:
        clean_query = _clean(query, MAX_QUERY_CHARS)
        if len(clean_query) < 4:
            return []
        url = self.ENDPOINT + "?" + urllib.parse.urlencode({
            "q": clean_query,
            "format": "json",
            "no_html": "1",
            "no_redirect": "1",
            "skip_disambig": "1",
        })
        request = urllib.request.Request(
            url,
            headers={
                "User-Agent": "JadeGenesis/0.1.17-night-learning-lab",
                "Accept": "application/json",
            },
            method="GET",
        )
        with urllib.request.urlopen(
            request,
            timeout=PUBLIC_RESEARCH_TIMEOUT_SECONDS,
        ) as response:
            final = urllib.parse.urlparse(response.geturl())
            if final.scheme != "https" or final.hostname != self.HOST:
                raise RuntimeError("public_research_redirect_rejected")
            raw = response.read(MAX_PUBLIC_RESPONSE_BYTES + 1)
        if len(raw) > MAX_PUBLIC_RESPONSE_BYTES:
            raise RuntimeError("public_research_response_too_large")
        parsed = json.loads(raw.decode("utf-8", errors="strict"))
        if not isinstance(parsed, dict):
            return []

        output: list[dict[str, Any]] = []
        abstract = _clean(parsed.get("AbstractText"), 360)
        abstract_url = _clean(parsed.get("AbstractURL"), 500)
        heading = _clean(parsed.get("Heading"), 160) or clean_query
        if abstract and abstract_url.startswith("https://"):
            output.append({
                "provider": "DuckDuckGo",
                "title": heading,
                "url": abstract_url,
                "snippet": abstract,
                "confidence": 0.66,
            })

        def visit(items: Any) -> None:
            if len(output) >= MAX_RESEARCH_EVIDENCE or not isinstance(items, list):
                return
            for item in items:
                if len(output) >= MAX_RESEARCH_EVIDENCE:
                    return
                if not isinstance(item, dict):
                    continue
                nested = item.get("Topics")
                if isinstance(nested, list):
                    visit(nested)
                    continue
                text = _clean(item.get("Text"), 360)
                first_url = _clean(item.get("FirstURL"), 500)
                if not text or not first_url.startswith("https://"):
                    continue
                output.append({
                    "provider": "DuckDuckGo",
                    "title": text[:160],
                    "url": first_url,
                    "snippet": text,
                    "confidence": 0.58,
                })

        visit(parsed.get("RelatedTopics"))
        return output[:MAX_RESEARCH_EVIDENCE]


class NightLearningLab:
    def __init__(self, research_provider: ResearchProvider | None = None):
        self.research_provider = research_provider or DuckDuckGoResearchProvider()

    def review(
        self,
        view: dict[str, Any],
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        source_revision = max(0, _safe_int(view.get("revision"), 0))
        runtime = _payload(_latest_entity(view, "runtime_eval_snapshot"))
        evolution = _payload(_latest_entity(view, "evolution_snapshot"))
        config = _payload(_latest_entity(view, "config_snapshot"))
        memory = _payload(_latest_entity(view, "memory_cursor"))

        groups_raw = runtime.get("groups", [])
        if not isinstance(groups_raw, list):
            groups_raw = []
        groups = [
            self._clean_group(item)
            for item in groups_raw
            if isinstance(item, dict)
        ][:MAX_RUNTIME_GROUPS]

        signals = self._signals(runtime, groups)
        questions = self._research_questions(signals, source_revision)
        researchable_questions = [
            item
            for item in questions
            if item.get("external_research_allowed", True) and item.get("query")
        ]
        evidence: list[dict[str, Any]] = []
        research_errors: list[str] = []
        for question in researchable_questions[:2]:
            try:
                found = self.research_provider.search(question["query"])
                for item in found:
                    clean_item = self._clean_evidence(item, question["question_id"])
                    if clean_item is not None:
                        evidence.append(clean_item)
            except Exception as exc:
                research_errors.append(
                    f"{question['question_id']}:{type(exc).__name__}"
                )
        evidence = self._dedupe_evidence(evidence)[:MAX_RESEARCH_EVIDENCE]

        hypotheses = self._hypotheses(
            signals=signals,
            evidence=evidence,
            source_revision=source_revision,
        )
        experiments = self._experiments(hypotheses, source_revision)
        candidates = self._candidates(experiments, source_revision)

        evolution_candidates = evolution.get("candidates", [])
        if not isinstance(evolution_candidates, list):
            evolution_candidates = []
        validated_existing = sum(
            1
            for item in evolution_candidates
            if isinstance(item, dict) and item.get("status") == "VALIDATED"
        )
        outcome_feedback_count = max(
            0,
            _safe_int(runtime.get("outcome_feedback_count"), 0),
        )
        overall_outcome_quality = min(
            1.0,
            max(-1.0, _safe_float(runtime.get("overall_outcome_quality"), 0.0)),
        )
        outcome_groups = sum(
            1 for group in groups
            if group["outcome_samples"] >= MIN_OUTCOME_SAMPLES
        )

        return {
            "schema_version": SCHEMA_VERSION,
            "generated_at": now,
            "source_revision": source_revision,
            "memory_cursor_available": bool(memory),
            "config_snapshot_available": bool(config.get("config")),
            "runtime_snapshot_available": bool(runtime),
            "runtime_observation_count": max(
                0,
                _safe_int(runtime.get("observation_count"), 0),
            ),
            "runtime_confidence": min(
                1.0,
                max(0.0, _safe_float(runtime.get("confidence"), 0.0)),
            ),
            "outcome_feedback_count": outcome_feedback_count,
            "overall_outcome_quality": overall_outcome_quality,
            "outcome_groups_with_minimum_evidence": outcome_groups,
            "outcome_consolidation_used": outcome_feedback_count > 0 or outcome_groups > 0,
            "raw_conversation_text_used": False,
            "user_feedback_promoted_to_external_fact": False,
            "existing_validated_evolution_candidates": validated_existing,
            "signals": signals[:MAX_HYPOTHESES],
            "research_questions": questions[:MAX_RESEARCH_QUESTIONS],
            "research_evidence": evidence[:MAX_RESEARCH_EVIDENCE],
            "research_errors": research_errors[:MAX_RESEARCH_QUESTIONS],
            "hypotheses": hypotheses[:MAX_HYPOTHESES],
            "experiments": experiments[:MAX_EXPERIMENTS],
            "improvement_candidates": candidates[:MAX_IMPROVEMENT_CANDIDATES],
            "external_research_attempted": bool(researchable_questions[:2]),
            "external_research_evidence_count": len(evidence),
            "automatic_experiment_execution": False,
            "automatic_promotion": False,
            "production_code_rewrite": False,
            "shell_execution": False,
        }

    def _clean_group(self, group: dict[str, Any]) -> dict[str, Any]:
        outcome_samples = max(0, _safe_int(group.get("outcome_samples"), 0))
        return {
            "node_id": _clean(group.get("node_id"), 120),
            "node_name": _clean(group.get("node_name"), 160),
            "task_kind": _clean(group.get("task_kind"), 100),
            "model": _clean(group.get("model"), 160),
            "brain_profile": _clean(group.get("brain_profile"), 100).lower(),
            "samples": max(0, _safe_int(group.get("samples"), 0)),
            "success_rate": min(
                1.0,
                max(0.0, _safe_float(group.get("success_rate"), 0.0)),
            ),
            "average_duration_ms": max(
                0.0,
                _safe_float(group.get("average_duration_ms"), 0.0),
            ),
            "fallback_rate": min(
                1.0,
                max(0.0, _safe_float(group.get("fallback_rate"), 0.0)),
            ),
            "average_tokens_per_second": max(
                0.0,
                _safe_float(group.get("average_tokens_per_second"), 0.0),
            ),
            "outcome_samples": outcome_samples,
            "positive_outcomes": min(
                outcome_samples,
                max(0, _safe_int(group.get("positive_outcomes"), 0)),
            ),
            "negative_outcomes": min(
                outcome_samples,
                max(0, _safe_int(group.get("negative_outcomes"), 0)),
            ),
            "corrections": min(
                outcome_samples,
                max(0, _safe_int(group.get("corrections"), 0)),
            ),
            "outcome_quality_score": min(
                1.0,
                max(-1.0, _safe_float(group.get("outcome_quality_score"), 0.0)),
            ),
            "outcome_confidence": min(
                1.0,
                max(0.0, _safe_float(group.get("outcome_confidence"), 0.0)),
            ),
        }

    def _signals(
        self,
        runtime: dict[str, Any],
        groups: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []
        observation_count = max(0, _safe_int(runtime.get("observation_count"), 0))
        confidence = min(1.0, max(0.0, _safe_float(runtime.get("confidence"), 0.0)))
        outcome_feedback_count = max(
            0,
            _safe_int(runtime.get("outcome_feedback_count"), 0),
        )
        overall_outcome_quality = min(
            1.0,
            max(-1.0, _safe_float(runtime.get("overall_outcome_quality"), 0.0)),
        )

        # Outcomes are personal operational evidence. They are prioritized when
        # sufficiently measured, but never treated as external factual truth.
        if 0 < outcome_feedback_count < MIN_OUTCOME_SAMPLES:
            output.append({
                "kind": "outcome_evidence_low",
                "task_kind": "brain_chat",
                "node_id": "",
                "model": "",
                "brain_profile": "",
                "samples": outcome_feedback_count,
                "metric": overall_outcome_quality,
                "outcome_confidence": 0.0,
            })

        outcome_groups = [
            group
            for group in groups
            if group["task_kind"] == "brain_chat"
            and group["outcome_samples"] >= MIN_OUTCOME_SAMPLES
            and group["outcome_confidence"] > 0.0
        ]
        poor_outcome = sorted(
            [
                group for group in outcome_groups
                if group["outcome_quality_score"] <= LOW_OUTCOME_QUALITY
            ],
            key=lambda item: (
                item["outcome_quality_score"],
                -item["outcome_confidence"],
                -item["outcome_samples"],
            ),
        )
        if poor_outcome:
            group = poor_outcome[0]
            output.append(self._outcome_signal("outcome_quality_risk", group))

        correction_pressure = sorted(
            [
                group for group in outcome_groups
                if group["corrections"] >= 2
                and group["corrections"] / max(1, group["outcome_samples"])
                >= CORRECTION_PRESSURE_RATE
            ],
            key=lambda item: (
                -(item["corrections"] / max(1, item["outcome_samples"])),
                -item["outcome_confidence"],
                -item["outcome_samples"],
            ),
        )
        if correction_pressure:
            group = correction_pressure[0]
            signal = self._outcome_signal("outcome_correction_pressure", group)
            signal["metric"] = group["corrections"] / max(1, group["outcome_samples"])
            output.append(signal)

        comparison = self._best_outcome_comparison(outcome_groups)
        if comparison is not None:
            best, worst, gap = comparison
            signal = self._outcome_signal("outcome_quality_advantage", best)
            signal.update({
                "metric": gap,
                "preferred_node_id": best["node_id"],
                "preferred_model": best["model"],
                "comparison_node_id": worst["node_id"],
                "comparison_model": worst["model"],
                "comparison_quality": worst["outcome_quality_score"],
                "comparison_samples": worst["outcome_samples"],
                "comparison_confidence": worst["outcome_confidence"],
            })
            output.append(signal)

        if observation_count < MIN_RUNTIME_SAMPLES or confidence < MIN_RESEARCH_CONFIDENCE:
            output.append({
                "kind": "runtime_evidence_low",
                "task_kind": "",
                "node_id": "",
                "model": "",
                "brain_profile": "",
                "samples": observation_count,
                "metric": confidence,
            })

        reliable_groups = [group for group in groups if group["samples"] >= MIN_GROUP_SAMPLES]
        poor_success = sorted(
            [group for group in reliable_groups if group["success_rate"] < LOW_SUCCESS_RATE],
            key=lambda item: (item["success_rate"], -item["samples"]),
        )
        if poor_success:
            group = poor_success[0]
            output.append({
                "kind": "reliability_bottleneck",
                "task_kind": group["task_kind"],
                "node_id": group["node_id"],
                "model": group["model"],
                "brain_profile": group["brain_profile"],
                "samples": group["samples"],
                "metric": group["success_rate"],
            })

        fallback = sorted(
            [group for group in reliable_groups if group["fallback_rate"] > HIGH_FALLBACK_RATE],
            key=lambda item: (-item["fallback_rate"], -item["samples"]),
        )
        if fallback:
            group = fallback[0]
            output.append({
                "kind": "fallback_pressure",
                "task_kind": group["task_kind"],
                "node_id": group["node_id"],
                "model": group["model"],
                "brain_profile": group["brain_profile"],
                "samples": group["samples"],
                "metric": group["fallback_rate"],
            })

        by_task: dict[str, list[dict[str, Any]]] = {}
        for group in reliable_groups:
            if group["average_duration_ms"] > 0:
                by_task.setdefault(group["task_kind"], []).append(group)
        latency_candidates: list[tuple[float, dict[str, Any]]] = []
        for items in by_task.values():
            if len(items) < 2:
                continue
            best = min(item["average_duration_ms"] for item in items)
            if best <= 0:
                continue
            for item in items:
                ratio = item["average_duration_ms"] / best
                if ratio >= LATENCY_OUTLIER_RATIO:
                    latency_candidates.append((ratio, item))
        if latency_candidates:
            ratio, group = max(latency_candidates, key=lambda pair: pair[0])
            output.append({
                "kind": "latency_outlier",
                "task_kind": group["task_kind"],
                "node_id": group["node_id"],
                "model": group["model"],
                "brain_profile": group["brain_profile"],
                "samples": group["samples"],
                "metric": ratio,
            })

        brain_groups = [
            group
            for group in reliable_groups
            if group["task_kind"] == "brain_chat" and group["average_tokens_per_second"] > 0
        ]
        if len(brain_groups) >= 2:
            best_tps = max(group["average_tokens_per_second"] for group in brain_groups)
            slow = min(brain_groups, key=lambda item: item["average_tokens_per_second"])
            if best_tps > 0 and slow["average_tokens_per_second"] / best_tps <= THROUGHPUT_OUTLIER_RATIO:
                output.append({
                    "kind": "throughput_outlier",
                    "task_kind": slow["task_kind"],
                    "node_id": slow["node_id"],
                    "model": slow["model"],
                    "brain_profile": slow["brain_profile"],
                    "samples": slow["samples"],
                    "metric": slow["average_tokens_per_second"] / best_tps,
                })

        if not output:
            output.append({
                "kind": "stable_observation",
                "task_kind": "",
                "node_id": "",
                "model": "",
                "brain_profile": "",
                "samples": observation_count,
                "metric": confidence,
            })
        return output[:MAX_HYPOTHESES]

    def _outcome_signal(
        self,
        kind: str,
        group: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "kind": kind,
            "task_kind": group["task_kind"],
            "node_id": group["node_id"],
            "model": group["model"],
            "brain_profile": group["brain_profile"],
            "samples": group["outcome_samples"],
            "metric": group["outcome_quality_score"],
            "outcome_confidence": group["outcome_confidence"],
            "positive_outcomes": group["positive_outcomes"],
            "negative_outcomes": group["negative_outcomes"],
            "corrections": group["corrections"],
        }

    def _best_outcome_comparison(
        self,
        outcome_groups: list[dict[str, Any]],
    ) -> tuple[dict[str, Any], dict[str, Any], float] | None:
        by_profile: dict[str, list[dict[str, Any]]] = {}
        for group in outcome_groups:
            profile = group["brain_profile"]
            if not profile or group["outcome_samples"] < MIN_OUTCOME_COMPARISON_SAMPLES:
                continue
            by_profile.setdefault(profile, []).append(group)

        candidates: list[tuple[float, dict[str, Any], dict[str, Any]]] = []
        for items in by_profile.values():
            if len(items) < 2:
                continue
            ordered = sorted(
                items,
                key=lambda item: (
                    item["outcome_quality_score"],
                    item["outcome_confidence"],
                    item["outcome_samples"],
                ),
                reverse=True,
            )
            best = ordered[0]
            worst = ordered[-1]
            gap = best["outcome_quality_score"] - worst["outcome_quality_score"]
            if gap >= MIN_OUTCOME_QUALITY_GAP:
                candidates.append((gap, best, worst))
        if not candidates:
            return None
        gap, best, worst = max(
            candidates,
            key=lambda item: (
                item[0],
                item[1]["outcome_confidence"],
                item[2]["outcome_confidence"],
            ),
        )
        return best, worst, gap

    def _research_questions(
        self,
        signals: list[dict[str, Any]],
        source_revision: int,
    ) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []
        for signal in signals:
            kind = signal["kind"]
            task = signal.get("task_kind", "")
            model = signal.get("model", "")
            node = signal.get("node_id", "")
            profile = signal.get("brain_profile", "")
            external_allowed = True
            if kind == "outcome_evidence_low":
                query = "Collecter davantage de retours utilisateur sur des réponses comparables"
                reason = "Les outcomes explicites sont encore trop rares pour consolider une préférence nocturne."
                external_allowed = False
            elif kind == "outcome_quality_risk":
                query = f"Comparer en interne les outcomes du profil {profile or 'inconnu'} pour {model or node or 'ce groupe'}"
                reason = "Les retours utilisateur agrégés indiquent une qualité négative mesurée; ce signal reste personnel et non universel."
                external_allowed = False
            elif kind == "outcome_correction_pressure":
                query = f"Comparer en interne le taux de corrections du profil {profile or 'inconnu'} pour {model or node or 'ce groupe'}"
                reason = "Une part importante des réponses mesurées a dû être corrigée par l'utilisateur."
                external_allowed = False
            elif kind == "outcome_quality_advantage":
                query = f"Valider en interne l'avantage outcome du profil {profile or 'inconnu'} sur scénarios appariés"
                reason = "Deux backends comparables montrent un écart d'Outcome Quality assez grand pour justifier un challenger borné."
                external_allowed = False
            elif kind == "runtime_evidence_low":
                query = (
                    "runtime evaluation sample size confidence adaptive routing "
                    "distributed inference benchmark"
                )
                reason = "Les preuves Runtime Eval sont encore trop faibles pour une décision d'évolution."
            elif kind == "reliability_bottleneck":
                query = f"{task} {model} distributed inference reliability failures routing fallback"
                reason = f"Le groupe {node or 'inconnu'} présente un taux de succès mesuré inférieur à 90 %."
            elif kind == "fallback_pressure":
                query = f"{task} distributed routing fallback rate retry reliability adaptive scheduler"
                reason = f"Le groupe {node or 'inconnu'} déclenche trop de fallbacks mesurés."
            elif kind == "latency_outlier":
                query = f"{task} distributed inference latency routing node selection benchmark"
                reason = f"Le groupe {node or 'inconnu'} est un outlier de latence face à un pair comparable."
            elif kind == "throughput_outlier":
                query = f"{model} Ollama tokens per second inference performance routing"
                reason = f"Le débit génératif mesuré du groupe {node or 'inconnu'} est nettement sous le meilleur pair."
            else:
                query = "adaptive routing runtime evaluation champion challenger experiment design"
                reason = "Les mesures sont stables; recherche de validation conservatrice avant tout changement."
            query = _clean(query, MAX_QUERY_CHARS)
            question_id = _stable_id("rq", source_revision, kind, query)
            output.append({
                "question_id": question_id,
                "query": query,
                "reason": _clean(reason),
                "signal_kind": kind,
                "external_research_allowed": external_allowed,
            })
        return output[:MAX_RESEARCH_QUESTIONS]

    def _clean_evidence(
        self,
        item: dict[str, Any],
        question_id: str,
    ) -> dict[str, Any] | None:
        url = _clean(item.get("url"), 500)
        if not url.startswith("https://"):
            return None
        title = _clean(item.get("title"), 160)
        snippet = _clean(item.get("snippet"), 360)
        if not title or not snippet:
            return None
        return {
            "evidence_id": _stable_id("ev", question_id, url),
            "question_id": question_id,
            "provider": _clean(item.get("provider"), 80) or "public",
            "title": title,
            "url": url,
            "snippet": snippet,
            "confidence": min(
                1.0,
                max(0.0, _safe_float(item.get("confidence"), 0.5)),
            ),
        }

    def _dedupe_evidence(self, items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []
        seen: set[str] = set()
        for item in items:
            key = item["url"].lower()
            if key in seen:
                continue
            seen.add(key)
            output.append(item)
        return output

    def _hypotheses(
        self,
        signals: list[dict[str, Any]],
        evidence: list[dict[str, Any]],
        source_revision: int,
    ) -> list[dict[str, Any]]:
        evidence_by_question = {
            item["question_id"]: item["evidence_id"] for item in evidence
        }
        output: list[dict[str, Any]] = []
        for signal in signals[:MAX_HYPOTHESES]:
            kind = signal["kind"]
            node = signal.get("node_id", "")
            task = signal.get("task_kind", "")
            model = signal.get("model", "")
            profile = signal.get("brain_profile", "")
            outcome_confidence = min(
                1.0,
                max(0.0, _safe_float(signal.get("outcome_confidence"), 0.0)),
            )
            if kind == "outcome_evidence_low":
                statement = "Les retours utilisateur explicites sont encore insuffisants pour apprendre une préférence de stratégie nocturne fiable."
                metric = "outcome_feedback_count"
                expected = f">= {MIN_OUTCOME_SAMPLES} outcomes actifs avant toute influence nocturne"
                confidence = 0.65
            elif kind == "outcome_quality_risk":
                statement = f"Pour le profil {profile or 'mesuré'}, {model or node or 'ce backend'} produit probablement moins souvent le résultat attendu par l'utilisateur que les alternatives comparables."
                metric = "outcome_quality_score"
                expected = "challenger >= champion + 0.25 en Outcome Quality, fiabilité protégée"
                confidence = min(0.90, 0.55 + 0.35 * outcome_confidence)
            elif kind == "outcome_correction_pressure":
                statement = f"Pour le profil {profile or 'mesuré'}, {model or node or 'ce backend'} provoque probablement trop de corrections utilisateur sur des tâches comparables."
                metric = "correction_rate"
                expected = "taux de corrections challenger inférieur d'au moins 0.15, fiabilité protégée"
                confidence = min(0.90, 0.55 + 0.35 * outcome_confidence)
            elif kind == "outcome_quality_advantage":
                preferred = signal.get("preferred_model") or signal.get("preferred_node_id") or "le backend mesuré"
                comparison = signal.get("comparison_model") or signal.get("comparison_node_id") or "son pair"
                statement = f"Pour le profil {profile or 'mesuré'}, {preferred} semble mieux satisfaire l'utilisateur que {comparison}, mais l'écart doit être reproduit avant promotion."
                metric = "outcome_quality_score_delta"
                expected = f"écart >= 0.25 avec >= {STRONG_OUTCOME_SAMPLES} outcomes par côté et fiabilité protégée"
                confidence = min(0.92, 0.60 + 0.30 * outcome_confidence)
            elif kind == "runtime_evidence_low":
                statement = "L'incertitude actuelle vient principalement d'un échantillon Runtime Eval encore insuffisant."
                metric = "runtime_eval.confidence"
                expected = ">= 0.75 avec au moins 12 observations"
                confidence = 0.72
            elif kind == "reliability_bottleneck":
                statement = f"Le nœud {node or 'mesuré'} est probablement moins fiable pour {task or 'cette tâche'} que les alternatives disponibles."
                metric = "success_rate"
                expected = "challenger >= champion - 0.02 et moins d'échecs"
                confidence = 0.78
            elif kind == "fallback_pressure":
                statement = f"La politique de routage actuelle sous-pénalise probablement les échecs récents de {node or 'ce groupe'}."
                metric = "fallback_rate"
                expected = "fallback_rate challenger < champion sans baisse de fiabilité"
                confidence = 0.76
            elif kind == "latency_outlier":
                statement = f"Le routage vers {node or 'ce groupe'} augmente probablement la latence de {task or 'la tâche'} sans bénéfice compensatoire mesuré."
                metric = "average_duration_ms"
                expected = "latence moyenne challenger < champion avec fiabilité protégée"
                confidence = 0.74
            elif kind == "throughput_outlier":
                statement = f"Le backend {node or 'mesuré'} fournit probablement un débit génératif inférieur à un pair déjà observé."
                metric = "average_tokens_per_second"
                expected = "tokens/s challenger > champion avec fiabilité protégée"
                confidence = 0.75
            else:
                statement = "Aucun bottleneck majeur n'est actuellement démontré; conserver le champion devrait rester préférable à une mutation spéculative."
                metric = "runtime_eval.score"
                expected = "aucun challenger sans delta objectif >= 2 points"
                confidence = 0.82

            question = self._research_questions([signal], source_revision)[0]
            evidence_ids = []
            evidence_id = evidence_by_question.get(question["question_id"])
            if evidence_id:
                evidence_ids.append(evidence_id)
                confidence = min(0.90, confidence + 0.05)
            hypothesis_id = _stable_id("hyp", source_revision, kind, statement)
            output.append({
                "hypothesis_id": hypothesis_id,
                "signal_kind": kind,
                "statement": _clean(statement),
                "confidence": confidence,
                "falsifiable_metric": metric,
                "success_criterion": _clean(expected),
                "evidence_ids": evidence_ids,
                "personal_outcome_evidence": kind.startswith("outcome_"),
                "promoted_as_external_fact": False,
            })
        return output

    def _experiments(
        self,
        hypotheses: list[dict[str, Any]],
        source_revision: int,
    ) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []
        for hypothesis in hypotheses[:MAX_EXPERIMENTS]:
            kind = hypothesis["signal_kind"]
            minimum_samples = MIN_RUNTIME_SAMPLES
            maximum_samples = MIN_RUNTIME_SAMPLES * 2
            if kind == "outcome_evidence_low":
                target = "outcome_eval"
                change_hint = "Collecter de nouveaux outcomes explicites sur des réponses comparables sans modifier le champion."
                metric = "outcome_feedback_count"
                minimum_samples = MIN_OUTCOME_SAMPLES
                maximum_samples = STRONG_OUTCOME_SAMPLES
            elif kind == "outcome_quality_risk":
                target = "routing.profile_model_preference"
                change_hint = "Tester en sandbox une préférence moindre pour le groupe à Outcome Quality négative, uniquement sur le même profil et des scénarios appariés."
                metric = "outcome_quality_score"
                minimum_samples = MIN_OUTCOME_COMPARISON_SAMPLES
                maximum_samples = STRONG_OUTCOME_SAMPLES * 2
            elif kind == "outcome_correction_pressure":
                target = "routing.profile_model_preference"
                change_hint = "Tester en sandbox un challenger qui réduit la préférence du groupe souvent corrigé, sans dégrader succès, latence ou fallback."
                metric = "correction_rate"
                minimum_samples = MIN_OUTCOME_COMPARISON_SAMPLES
                maximum_samples = STRONG_OUTCOME_SAMPLES * 2
            elif kind == "outcome_quality_advantage":
                target = "routing.profile_model_preference"
                change_hint = "Rejouer un champion/challenger apparié entre les deux backends du même profil pour confirmer l'avantage Outcome Quality avant toute préférence durable."
                metric = "outcome_quality_score_delta"
                minimum_samples = MIN_OUTCOME_COMPARISON_SAMPLES
                maximum_samples = STRONG_OUTCOME_SAMPLES * 2
            elif kind == "runtime_evidence_low":
                target = "runtime_eval"
                change_hint = "Collecter un nouveau lot apparié sans modifier le champion."
                metric = "confidence"
            elif kind == "reliability_bottleneck":
                target = "routing"
                change_hint = "Tester un challenger qui réduit la préférence du groupe peu fiable, sans dépasser SafetyPolicy."
                metric = "success_rate"
            elif kind == "fallback_pressure":
                target = "routing.history_failure_penalty"
                change_hint = "Tester une légère pénalisation supplémentaire des échecs récents dans une sandbox champion/challenger."
                metric = "fallback_rate"
            elif kind == "latency_outlier":
                target = "routing"
                change_hint = "Tester une préférence moindre pour l'outlier de latence sur des scénarios identiques."
                metric = "average_duration_ms"
            elif kind == "throughput_outlier":
                target = "routing.brain_tokens_per_second_weight"
                change_hint = "Tester une pondération légèrement plus forte du débit mesuré, bornée par SafetyPolicy."
                metric = "average_tokens_per_second"
            else:
                target = "observation_only"
                change_hint = "Ne modifier aucun paramètre; prolonger l'observation jusqu'à apparition d'un signal reproductible."
                metric = "runtime_eval.score"
            experiment_id = _stable_id(
                "exp",
                source_revision,
                hypothesis["hypothesis_id"],
                target,
            )
            output.append({
                "experiment_id": experiment_id,
                "hypothesis_id": hypothesis["hypothesis_id"],
                "target": target,
                "change_hint": _clean(change_hint),
                "primary_metric": metric,
                "minimum_samples_per_side": minimum_samples,
                "maximum_samples_per_side": maximum_samples,
                "paired_scenarios_required": True,
                "frozen_champion_required": True,
                "sandbox_required": target not in {"observation_only", "outcome_eval"},
                "automatic_execution": False,
                "requires_explicit_promotion_approval": True,
            })
        return output

    def _candidates(
        self,
        experiments: list[dict[str, Any]],
        source_revision: int,
    ) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []
        for experiment in experiments[:MAX_IMPROVEMENT_CANDIDATES]:
            observation_only = experiment["target"] in {
                "runtime_eval",
                "outcome_eval",
                "observation_only",
            }
            strategy = experiment["target"] == "routing.profile_model_preference"
            if observation_only:
                kind = "EVIDENCE_COLLECTION"
                title = "Renforcer les preuves avant évolution"
            elif strategy:
                kind = "STRATEGY_HINT"
                title = "Challenger de stratégie fondé sur les outcomes"
            else:
                kind = "CONFIG_HINT"
                title = f"Challenger borné pour {experiment['target']}"
            candidate_id = _stable_id(
                "nlc",
                source_revision,
                experiment["experiment_id"],
                kind,
            )
            output.append({
                "candidate_id": candidate_id,
                "kind": kind,
                "status": "CANDIDATE",
                "title": _clean(title, 180),
                "rationale": experiment["change_hint"],
                "experiment_id": experiment["experiment_id"],
                "target": experiment["target"],
                "sandbox_required": bool(experiment["sandbox_required"]),
                "automatic_activation": False,
                "automatic_promotion": False,
                "production_code_change": False,
            })
        return output
