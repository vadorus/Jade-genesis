# Handoff — 0.1.21 V3 live proof (2026-09-16)

Written for whoever resumes this work (Codex or another agent). It records what was done, where everything lives, how it was verified, and what is still open. The external evidence log is issue #27; this file does not replace it.

## 1. Outcome in one paragraph

Protocol `0.1.21-first-acquisition-v3` completed the full formal chain on the live VPS + Pixel: V3 attestations → hybrid teacher → one frozen candidate → one-shot hidden exam PASS (dataset 0001, 2/2) → Skill `normalize_label_v1@1` retained and routed → real Node Runtime restart → real Pixel `/normalize` with the precommitted vector returned `éclair-ça_fête42` with `ollama_calls_delta = 0` and `post_restart_proof_eligible = true` → negative control (route removed) returned to a counted model call. The route was then re-activated. The allowed claim is now: **"Jade demonstrated persistent acquisition and reuse of one bounded procedure."** Nothing broader.

Provenance must always be stated with that claim: the retained body was assembled by deterministic typed search from hints of a `qwen3:4b` proposal (`typed_search_with_llm_hints`), not synthesized by the LLM alone.

## 2. Timeline and evidence (issue #27)

| UTC (16/09) | Step | Evidence |
|---|---|---|
| 15:44 | V2 runtime `15c88f1` deployed (by the previous agent) | #issuecomment-5700929173 (md5 verification) |
| 16:31 | V2 live: attestations OK, first teacher call → truncated JSON → uncaught `ValueError`; goal V2 stuck `VISIBLE_TESTING`, 0 candidates, no exam | #issuecomment-5700996893 |
| — | Root cause + local benchmark of PC models; V3 written, CI run 35125594221 green (123 tests) | branch `jade/0.1.21-skill-teacher-v3`, commit `61d0dbd` |
| 17:0x | V3 precommit published before deployment | #issuecomment-5701443125 |
| 17:08 | V3 deployed, 26/26 md5 = `git show 61d0dbd`, teacher config added | #issuecomment-5701461455 |
| 17:09:55 | V3 attestations (events 18–20), teacher (21–22), candidate 1 frozen, exam PASS, `skill_retained` (23) | #issuecomment-5701489526 |
| 17:10:59 | Real restart PID 43528 → 43807 | same |
| 17:20:40 | Pixel vector → `skill_used` (24), `real_skill_reuse` (25), delta 0 | #issuecomment-5701705861 |
| 17:21–17:25 | Negative control: route removed, Pixel `/normalize  NéGaTiF-TesT7` → `chat_calls` 0→1, Ollama 1m57s, no reuse event | same |
| 17:25 | Same verified Skill re-activated (route revision 3), PID 47767 | same |

Key identifiers:
- goal `first-real-normalize-label-v3` = `RETAINED`, `candidate_count=1`
- `selected_spec_sha256 = 22ef15f9864d80a0311300c0316cd7a05f49134b36ac7ec8e765e5819fccaa4b`
- exam `682bc34922646904fc3cb68e` on `normalize-label-real-0001`
- body: `object("text", trim(lower(get("text"))))`
- identity `JG-444331ab-46fb-43d1-87d5-f235da7bdd73`

## 3. What V3 changed (code)

See `docs/LEARNING_TRIAL_POLICY_0.1.21_V3.md` for the policy. Code, all in `node-agent/`:

- `resilient_skill_synthesis.py` — teacher call moved so a `ValueError` from the teacher is a rejected candidate; other exceptions still fail closed. Tests: `test_proof_store_hardening.py` (`test_malformed_teacher_response_is_a_rejected_candidate`, `test_teacher_transport_failure_still_fails_closed`).
- `ollama_skill_teacher.py` — `RESPONSE_SCHEMA` (strict JSON schema) instead of `"format": "json"`, `think: false`; `teacher_ollama_url` / `teacher_model` config keys.
- `typed_skill_search.py` (new) — deterministic BFS over string ops (`trim`, `lower`, `upper`, `concat`), observational-equivalence pruning, 20 000 evaluations, depth 3, visible cases only.
- `hybrid_skill_teacher.py` (new) — LLM first; if its body does not reproduce visible cases, search with its ops/literals as hints; search alone if LLM unavailable/malformed; `PermissionError` never swallowed.
- `learning_trial_protocol.py` / `skill_learning_cycle.py` — V3 ids, `HybridSkillTeacher(OllamaSkillTeacher(...))`, teacher id `hybrid-teacher-v3`.
- `.github/workflows/jade-node-runtime-ci.yml` — V3 assertions; compile list now includes `resilient_skill_synthesis.py`, `proof_store_hardening.py`, `shared_store_lock.py` and the new modules/tests.

## 4. Where things live

Local (Alexandre's PC, Windows):
- main clone `C:\Users\alexa\JadeGenesisRuntime` (branch `main`, has untracked `tmp_*.py` helper scripts from earlier V1 work — not committed, not ours to delete)
- V3 worktree `C:\Users\alexa\JadeGenesisRuntime-v3` (branch `jade/0.1.21-skill-teacher-v3`)
- PC Ollama 0.34 on `0.0.0.0:11434`, models `qwen3:4b`, `gemma3:4b`, `mistral`; GPU GTX 1660 Ti 6 GB; Tailscale IP `100.98.238.6`

VPS (`jade-genesis-vps`, Tailscale `100.79.99.100`; SSH settings in the JADE Hub `.env`):
- runtime `/opt/jade-genesis-node` (no git; 26 modules = `61d0dbd`), service `jade-genesis-node` (User `jade-genesis`, port 8766 allowed only on `tailscale0`)
- config/state `/var/lib/jade-genesis/config/` — `node-agent.json` (token, `learning_trial_enabled=false`, `teacher_ollama_url`, `teacher_model`), `workshop/`, `archive/`, `production/`
- backups: `/opt/jade-genesis-node.pre-v2-15c88f12`, `/opt/jade-genesis-node.pre-v3-20260916T170837Z`, `node-agent.json.pre-v3-teacher-20260916T170904Z.bak`
- operator scripts + logs of V2/V3 runs: `/root/jade-genesis-ops-20260916/` (`jade_v3_run.py attest|learn`, `jade_v3_wrapper.sh`, `jade_v3_deactivate.py`, `jade_v3_reactivate.py`, run logs, expected md5 list)
- VPS Ollama 0.34, only `qwen2.5-coder:3b`, CPU only (4 cores, 7 GB RAM)

## 5. How things were done (reuse these patterns)

- **Health:** `/health` needs header `X-Jade-Token` from `node-agent.json`; `/root/…` or local `JadeGenesisRuntime/tmp_health_trial.py` piped to `ssh … python3 -`.
- **One-off store writes:** stop `jade-genesis-node` first (store locks are process-local), run as `runuser -u jade-genesis -- env JADE_GENESIS_CONFIG_DIR=/var/lib/jade-genesis/config python3 …` from `/opt/jade-genesis-node`, then start the service.
- **Learning run:** `systemd-run --unit=… /root/jade-genesis-ops-20260916/jade_v3_wrapper.sh` (stops service, `attest` then `learn` with `learning_trial_enabled` set in memory only, restarts service on exit). Never persist `learning_trial_enabled=true`.
- **Deploy check:** compare `md5sum /opt/jade-genesis-node/*.py` with `git show <sha>:node-agent/<file> | md5sum`.
- **GitHub:** `gh` is not installed on the PC; issue comments were posted through Alexandre's logged-in Chrome. CI status is readable anonymously at `api.github.com/repos/vadorus/Jade-genesis/actions/runs?head_sha=<sha>`.
- **Python on the PC:** `python` is not on PATH; use `py -3.12`.

## 6. Pitfalls met

1. `git archive` on this Windows checkout applies CRLF conversion. The first V3 copy had CRLF in all 26 files (md5 mismatch). Fixed with `sed -i 's/\r$//'` and re-verified before any run. Use `git show <sha>:path` blobs or strip CR before deploying.
2. Plain Ollama `"format": "json"` + 512 tokens lets small models echo the DSL contract until truncation. Keep the schema.
3. `/normalize` on Android strips the prefix `"/normalize "` (one space). The precommitted vector starts with a space, so the Pixel message needs two spaces.
4. A real `/normalize` while no route is active enrolls a real case into the next unsealed dataset (0004 now has 2 cases, one of which is the negative-control input `NéGaTiF-TesT7`).
5. Never send the proof vector or any `REAL_USER`-marked request from tooling; only the Pixel counts as real traffic.

## 7. Open items (not done)

- **PR:** draft PR #30 (this branch) supersedes draft PR #29 (V2), which is still open and should be closed once #30 is reviewed. Nothing is merged to `main`.
- **Goal V2** remains `VISIBLE_TESTING` (historical, intentionally untouched).
- **Durability issue** noted in #27 before V2: real-case collection can fail between fifth-case persistence, case-pack materialization and attribution append. Must be fixed before collecting confirmatory datasets.
- **Post-demo confirmation** required by policy: 4 fresh confirmatory datasets / 10 hidden cases before any stronger claim; 0002/0003 stay sealed and unconsumed.
- **Router-level dispatch** still pending (`router_level_skill_dispatch_pending`): Skill lookup happens inside Node `brain_chat`, after Android node selection.
- **Architecture debt found during the audit (not addressed):** Android `MemoryStore.searchForContext` is never called (context is recency-based); `EvolutionEngine` has no caller; adaptive strategy registry had 0 entries after 10 night cycles; `max_dependencies = 0` blocks Skill composition.
- **Temporary files:** `/tmp/jade_v*_*.py` on the VPS are copies of the archived scripts and can be removed.
