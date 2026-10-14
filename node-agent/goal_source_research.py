"""Bounded goal-to-official-docs investigation for Jade Genesis.

A conservative first implementation. It autonomously chooses which APPROVED
source(s) fit a supplied objective, fetches them from pinned HTTPS URLs, extracts
relevant source-grounded passages, and schedules counterchecks. It does NOT
decide that a passage is true, settle contradictions, or certify experiments.
No user goal is sent to an arbitrary search provider. No unapproved network URL.
"""
from __future__ import annotations

import argparse
import hashlib
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
import unicodedata
from typing import Any, Callable
import urllib.request

from official_source_capture import (
    APPROVED, CaptureFailure, _open_only_official, fetch_source_with_body,
)

SCHEMA = "jade.goal_source_research.v1"
MAX_GOAL_CHARS = 320
MAX_BLOCKS = 3500
MAX_EXCERPTS = 5
MAX_EXCERPT_CHARS = 380
MAX_CONTENT_SCAN = 900_000
IGNORED_HTML = frozenset({
    "script", "style", "noscript", "svg", "head", "nav", "footer",
    "header", "form", "button", "template", "aside",
})
BLOCK_TAGS = frozenset({"p", "li", "h1", "h2", "h3", "blockquote", "dt", "dd"})
WORD_RE = re.compile(r"[a-z0-9]+")
STOP = frozenset({
    "avec", "dans", "pour", "comment", "faire", "creer", "creation", "mon",
    "mes", "une", "des", "les", "sur", "son", "ses", "par", "vers", "sans",
    "plus", "que", "qui", "quelle", "quoi", "veux", "souhaite", "faire",
    "aide", "apprendre", "utiliser", "utilise", "est", "jeu", "projet",
})
ASSET_TERMS = frozenset({
    "asset", "assets", "mesh", "maillage", "modele", "model", "modele",
    "arbre", "tree", "texture", "textures", "materiaux", "material",
    "rig", "animation", "import", "importer", "export", "exporter",
    "fbx", "glb", "gltf", "obj", "geometry", "geometrie", "scene", "scenes",
    "plugin", "addon", "integration", "transfer", "transfert",
})
LIMITATION_HINTS = frozenset({
    "not supported", "unsupported", "cannot", "does not", "doesn't",
    "not available", "limitation", "limitations", "do not",
    "not recommended", "may not", "warning", "not possible",
    "not work", "however", "unexpectedly", "scale issues",
    "not retained", "not preserved", "mismatch", "may differ", "will not",
    "ne peut pas", "pas pris en charge", "non pris en charge",
    "n'est pas", "ne fonctionne pas", "attention",
})


def normalize(value: str) -> str:
    return "".join(
        character for character in
        unicodedata.normalize("NFKD", value.lower())
        if not unicodedata.combining(character)
    )


def tokens(value: str) -> set[str]:
    return {w for w in WORD_RE.findall(normalize(value)) if len(w) >= 3 and w not in STOP}


def select_sources(goal: str) -> tuple[list[str], str]:
    """Choose from a fixed official catalog. Fail closed on unknown domains."""
    if not isinstance(goal, str) or not goal.strip() or len(goal) > MAX_GOAL_CHARS:
        raise ValueError("Expected a nonblank goal of <=320 characters")
    words = tokens(goal)
    bl = "blender" in words
    rb = "roblox" in words
    if not (bl or rb) or not words.intersection(ASSET_TERMS):
        return [], "NO_SUPPORTED_TOPIC"
    keys: list[str] = []
    if rb:
        if bl or words.intersection({"plugin", "addon", "integration"}):
            keys.append("roblox_blender")
        if words.intersection(ASSET_TERMS):
            keys.append("roblox_export")
    if bl and (rb or words.intersection({"fbx", "export", "exporter", "import", "importer", "asset", "model", "modele", "mesh"})):
        keys.append("blender_fbx")
    return list(dict.fromkeys(keys))[:3], "APPROVED_DOCS_SELECTED" if keys else "NO_SUPPORTED_TOPIC"


class _BlockExtractor(HTMLParser):
    """Extract text nodes from readable block elements without scripts/markup."""
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[str] = []
        self.blocks: list[tuple[str, str]] = []
        self.active_tag: str | None = None
        self.active_level = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if self.active_tag is None and tag in BLOCK_TAGS and not (
            set(self.stack) & IGNORED_HTML
        ) and len(self.blocks) < MAX_BLOCKS:
            self.active_tag = tag
            self.active_level = len(self.stack)
            self.parts = []
        self.stack.append(tag)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        # e.g. <br/> must not leave a dangling stack frame.
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_data(self, data: str) -> None:
        if self.active_tag and not (set(self.stack) & IGNORED_HTML):
            self.parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if self.active_tag == tag and self.active_level < len(self.stack):
            block = " ".join(" ".join(self.parts).split())
            if len(block) >= 45 and len(self.blocks) < MAX_BLOCKS:
                self.blocks.append((tag, html.unescape(block)[:2000]))
            self.active_tag = None
            self.parts = []
        if tag in self.stack:
            # Malformed HTML is common. Recover conservatively.
            index = len(self.stack) - 1 - self.stack[::-1].index(tag)
            del self.stack[index:]


def extract_passages(
    raw: bytes,
    *,
    mime: str,
    query: str,
    max_excerpts: int = MAX_EXCERPTS
) -> list[dict[str, Any]]:
    if len(raw) > MAX_CONTENT_SCAN:
        raw = raw[:MAX_CONTENT_SCAN]
    decoded = raw.decode("utf-8", errors="replace")
    if mime == "text/plain":
        blocks = [("text", part.strip()) for part in decoded.splitlines() if len(part.strip()) >= 45]
    else:
        parser = _BlockExtractor()
        parser.feed(decoded)
        blocks = parser.blocks
    target = tokens(query)
    ranked: list[tuple[int, int, str, str]] = []
    seen = set()
    for index, (tag, body) in enumerate(blocks):
        passage = " ".join(body.split())[:MAX_EXCERPT_CHARS]
        if not passage or passage in seen:
            continue
        matches = tokens(passage) & target
        if not matches:
            continue
        score = len(matches) * 4
        if "fbx" in matches:
            score += 3
        if tag in {"h1", "h2", "h3"}:
            score += 1
        seen.add(passage)
        ranked.append((score, index, tag, passage))
    ranked.sort(key=lambda row: (-row[0], row[1]))
    excerpts = []
    for score, index, tag, passage in ranked[:max_excerpts]:
        normalized = normalize(passage)
        possible_caveat = any(hint in normalized for hint in LIMITATION_HINTS)
        excerpts.append({
            "block_index": index,
            "element": tag,
            "excerpt": passage,
            "excerpt_sha256": hashlib.sha256(passage.encode("utf-8")).hexdigest(),
            "matching_terms": sorted(tokens(passage) & target)[:12],
            "relevance_score": score,
            "potential_limitation": possible_caveat,
            "stance": "UNKNOWN",
            "inspected": False,
            "untrusted_external_text": True,
            "verification": "NOT_VERIFIED",
        })
    return excerpts


def _countercheck_questions(goal: str, selected: list[str]) -> list[str]:
    if not selected:
        return ["Identifier d'abord une documentation primaire pertinente pour l'objectif."]
    output = [
        "Comparer les restrictions ou cas non pris en charge entre les sources officielles.",
        "Vérifier en expérimentation isolée ce qui concerne l'export/import réel.",
        "Relever les dates et changements éventuels de version des outils.",
    ]
    if "roblox_export" in selected:
        output.append("Contrôler dans Roblox Studio la fidélité des matériaux et du modèle importé.")
    return output[:4]


def investigate_goal(
    goal: str,
    *,
    opener: Callable[[urllib.request.Request, float], Any] = _open_only_official,
    now_ms: int | None = None
) -> dict[str, Any]:
    selected, selection_status = select_sources(goal)
    current = int(now_ms if now_ms is not None else time.time() * 1000)
    sources: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []
    for key in selected:
        try:
            metadata, raw = fetch_source_with_body(
                APPROVED[key], opener=opener, now_ms=current
            )
            excerpts = extract_passages(raw, mime=metadata["mime"], query=goal)
            sources.append({
                **metadata,
                "excerpts": excerpts,
                "possible_caveats": [
                    {"excerpt_sha256": p["excerpt_sha256"],
                     "excerpt": p["excerpt"],
                     "review_status": "POTENTIAL_RESTRICTION_NOT_PROVEN"}
                    for p in excerpts if p["potential_limitation"]
                ],
                "excerpt_selection": "LEXICAL_UNVERIFIED",
                "direction": "UNKNOWN",
                "fact_verified": False,
                "body_persisted": False,
            })
        except (CaptureFailure, UnicodeError, ValueError) as exc:
            errors.append({"source_key": key, "reason": str(exc)[:160]})
    return {
        "schema": SCHEMA,
        "goal": goal.strip(),
        "selection_status": selection_status,
        "created_at_ms": current,
        "selected_source_keys": selected,
        "sources": sources,
        "errors": errors,
        "countercheck_questions": _countercheck_questions(goal, selected),
        "contradictions_proven": False,
        "automatic_verification": False,
        "automatic_promotion": False,
        "tool_execution": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Explore approved official docs for a technical goal")
    parser.add_argument("--goal", required=True, help="Brief French or English technical question")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--authorization-store", type=Path, required=True,
                        help="Local journal containing the operator's exact-plan grant")
    parser.add_argument("--authorization-id", required=True)
    arguments = parser.parse_args()
    destination = arguments.out.expanduser().resolve()
    if destination.exists() or not destination.parent.is_dir():
        raise SystemExit("Output already exists or its parent folder is absent")
    from night_learning_lab import NightLearningLab
    from research_authorization import ResearchAuthorizationStore
    report = NightLearningLab().research_official_goal(
        arguments.goal,
        authorization_store=ResearchAuthorizationStore(arguments.authorization_store),
        authorization_id=arguments.authorization_id,
    )
    with destination.open("x", encoding="utf-8") as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({
        "report_path": str(destination),
        "selection": report["selection_status"],
        "sources": len(report["sources"]),
        "excerpts": sum(len(s["excerpts"]) for s in report["sources"]),
        "errors": report["errors"],
        "conclusions_verified": False,
    }, ensure_ascii=False))
    return 0 if not report["errors"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
