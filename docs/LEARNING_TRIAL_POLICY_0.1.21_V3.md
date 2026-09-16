# Jade Genesis 0.1.21 — Learning Trial Policy V3

Protocol ID: `0.1.21-first-acquisition-v3`

This document is the precommitment for the third visible-learning attempt of the first real `normalize_label_v1` acquisition experiment. It exists because protocol V2 was interrupted during its first live teacher call before any candidate was evaluated, frozen or examined.

## V2 outcome and why V3 is allowed

Live V2 ran on 2026-09-16 on runtime `15c88f1` (evidence: issue #27).

- V2 attestations were recorded for datasets 0001/0002/0003.
- The first Ollama `/api/chat` call returned HTTP 200 after 1m42s, but its content contained no parseable JSON object.
- `resilient_skill_synthesis.py` called the teacher outside the `try` block that turns an invalid proposal into a rejected candidate, so `ValueError: teacher_response_json_object_required` aborted `learn()`.
- Goal `first-real-normalize-label-v2` remains `VISIBLE_TESTING` with `candidate_count=0`. Per V2 policy this is `GOAL_INTERRUPTED_MANUAL_REVIEW`; it is not resumed.
- No candidate was frozen, no SEALED_TEST ran, no Skill or route exists.

The failure mode was reproduced locally with synthetic cases: under plain `"format": "json"` a small model echoed the DSL contract until the 512-token cap and returned truncated JSON.

A local benchmark on synthetic cases also showed that small models (`qwen3:4b`, `gemma3:4b`, `mistral`) name the right operations (`get "text"`, `trim`, `lower`) but do not assemble a valid AST, even with four candidates and visible diagnostics (0/12 for `qwen3:4b`).

V3 is therefore a new explicit protocol revision, not a continuation of the V2 goal.

## What changes in V3

1. **Malformed teacher responses are rejected candidates.** A `ValueError` raised by the teacher (invalid/truncated JSON, forbidden fields, missing body) consumes one candidate slot and is persisted as a visible diagnostic. Transport, availability and hidden-data guard errors still fail closed exactly as in V2.
2. **Strict response schema.** The Ollama request uses a JSON schema (`skill_id`, `description`, `domain`, `body` only) instead of plain JSON mode, and disables model thinking output. `num_predict=512` and the 420 s transport timeout are unchanged.
3. **Hybrid teacher.** `HybridSkillTeacher` wraps the Ollama teacher:
   - an LLM proposal that already reproduces every visible case is returned unchanged;
   - otherwise its operation names and string literals become hints for `typed_skill_search`, a deterministic, bounded (20 000 evaluations, depth 3) breadth-first search over string operations that assembles the smallest body reproducing every visible TRAIN/VALIDATION output;
   - if the LLM is unavailable or malformed, the search runs without hints.
4. **Teacher node.** `teacher_ollama_url` and `teacher_model` may point the LLM teacher at another node (the PC GPU over Tailscale). Without them the local Ollama is used.
5. Identifiers: goal `first-real-normalize-label-v3`, teacher id `hybrid-teacher-v3`, source model `ollama-code-profile+typed-search`.

The teacher request kind remains `JADE_SKILL_TEACHER_REQUEST_V2`: the request content is unchanged.

## What does not change

Verifier, task oracle, hidden partition, seal hashes, partition plan, candidate limit (4), frozen-candidate persistence before the exam, the one-shot hidden exam, retention/activation gates, the post-restart vector and the negative control are unchanged.

## Hidden-data boundary

The typed search receives only the `visible_cases` of the teacher request and filters them again to TRAIN/VALIDATION. It has no access to the ledger, SEALED_TEST inputs or answers, the seal nonce or verifier internals. The hybrid teacher refuses any request whose hidden-data exposure flags are not explicitly `false`, before calling the LLM.

## Reuse of the already sealed datasets

Datasets 0001/0002/0003 remain eligible because neither V1 nor V2 ran a hidden exam and their sealed commitments are unchanged. Attestations are protocol-scoped, so fresh V3 attestation events referencing the original external publications (#issuecomment-5638546101, #issuecomment-5640212399, #issuecomment-5648404451) must be recorded before the V3 teacher starts.

Goals V1 (`VISIBLE_FAILED`) and V2 (`VISIBLE_TESTING`) remain historical and are not modified.

## Failure policy

Unchanged from V2 and scoped to `0.1.21-first-acquisition-v3`: at most one failed SEALED examination; a hidden failure is terminal for V3; the failed frozen candidate may not be submitted to another dataset; reserve datasets are not retry budget; an interrupted `VISIBLE_TESTING` goal fails closed without automatic retry.

## Claim discipline

If V3 succeeds, the provenance of the retained Skill must be reported precisely: which path produced it (`llm`, `typed_search_with_llm_hints` or `typed_search_only`) is recorded in the teacher status and must be published with the result.

A Skill assembled by typed search from LLM hints demonstrates persistent acquisition and reuse of one bounded procedure through Jade's verified pipeline. It does not demonstrate that the LLM alone can synthesize procedures, nor general task-family mastery.

Before the complete proof succeeds the correct claim remains:

> 0.1.21 causal-learning mechanism implemented; live persistent acquisition not yet demonstrated.
