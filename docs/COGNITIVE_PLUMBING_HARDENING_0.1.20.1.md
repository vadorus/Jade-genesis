# Jade Genesis 0.1.20.1 — Cognitive Plumbing Hardening

## Purpose

0.1.20 introduced the restricted deterministic SkillSpec execution substrate. A subsequent code audit found several Android-side paths where data or governance existed but could be neutralized before it affected later behavior.

0.1.20.1 is deliberately a **plumbing hardening** milestone. It does not claim autonomous Skill acquisition. Its job is to make existing memory, synchronization and evaluation signals survive the path from production to consumption before 0.1.21 adds synthesis.

## 1. Durable memory delivery

### Problem

`JadeCore.ask()` can prepare more memories than `LocalPCBrain` finally serializes. The previous brain implementation sorted `JADE_CONSOLIDATION_*` memories after ordinary memories and then truncated the list. Under recent-memory pressure, consolidated knowledge could therefore be produced successfully and still never reach the selected model.

### 0.1.20.1 behavior

`MemoryStore.latestForContext()` now builds a bounded cognitive mix that reserves small slots for:

- active `USER` facts;
- active `JADE_CONSOLIDATION_*` knowledge;
- then recent active memories for the remaining budget.

`LocalPCBrain` preserves this prepared ordering and only applies its final bound. It no longer explicitly deprioritizes consolidated knowledge.

Only memories actually returned for reasoning are marked recalled.

### Boundary

This is deterministic context selection, not semantic retrieval. Relevance-aware memory ranking remains future work.

## 2. Memory lifecycle cleanup

### Exact duplicate supersession

After a consolidation completes successfully, the lifecycle can now mark exact normalized textual duplicates from that processed batch as superseded.

The operation is intentionally conservative:

- same memory type is required;
- text equivalence is limited to trim/case/whitespace normalization;
- `USER` facts are never automatically superseded;
- verified/higher-confidence/newer memories are preferred as the survivor;
- heuristic contradiction detection does **not** trigger deletion or supersession.

This activates a previously unused supersession path without pretending that approximate semantic similarity establishes truth.

### Transient visual observations

Normal targeted/PC visual observations are currently written around confidence `0.68`. The generic low-confidence retention threshold could be lower than that, making transient screen observations effectively immune to expiration.

0.1.20.1 adds compiled `MAX_TRANSIENT_VISION_RETENTION_CONFIDENCE = 0.70`. Old, unverified, weakly recalled `VISION_*` observations can therefore expire after the configured bounded retention age while USER facts and protected memories remain excluded.

### Verification boundary

`markVerified()` is **not** automatically invoked merely because two memories agree. Agreement is evidence of duplication, not external truth. Future verification must have a provenance-aware rule.

## 3. Shared Genesis State cache compaction

### Problem

The phone cache is bounded, but operational snapshots receive new event UUIDs on every publication. Deduplication by event ID alone therefore treats repeated state snapshots as new history and can churn through the cache, potentially evicting rarer VPS/Night Learning events.

### 0.1.20.1 policy

The phone cache now semantically coalesces these high-frequency operational kinds:

- `identity_presence`;
- `config_snapshot`;
- `phone_node_snapshot`;
- `memory_cursor`;
- `resource_lease_snapshot`;
- `runtime_eval_snapshot`;
- `evolution_snapshot`.

For these kinds, only the newest event for `(originNode, kind, entityId)` is retained in the phone cache.

Non-operational events, including learning/night-cycle reports, continue to deduplicate only by `eventId` and therefore retain their history until the global bounded cache limit genuinely requires eviction.

The server-side durable event stream and latest-entity representation are not replaced by this phone-cache policy.

## 4. Evolution evidence hardening

The Evolution Engine remains intentionally dormant from autonomous production use, but its metrics are repaired before any future wiring.

### Independent evidence confidence

Previously Runtime Eval reached confidence 1.0 at the same 12 observations required as the minimum Evolution trial size. That made Evolution's 0.75 confidence gate unable to reject a minimally sized trial.

0.1.20.1 defines an Evolution-specific curve:

```text
< 12 observations -> 0.0
12 observations   -> 0.50
18 observations   -> 0.75
24 observations   -> 1.00
```

The sample-count guard and confidence guard now test different properties.

### Unsaturated comparison score

Runtime Eval still publishes the familiar bounded `score` in `0..100` for UI/operational reporting, but it also preserves `rawScore` before clipping.

Evolution evidence uses `rawScore`. This prevents a champion displayed at `100` from making every positive `MIN_EVOLUTION_SCORE_DELTA` mathematically impossible when the underlying objective function can distinguish scores above 100.

The Shared Genesis State runtime snapshot also includes `raw_score` so this evidence is not destroyed at the synchronization boundary.

### Still intentionally inactive

0.1.20.1 does **not** automatically call:

- `proposeConfigCandidate()`;
- `beginSandboxTrial()`;
- `recordPairedSandboxEvaluation()`;
- `promote()`;
- `rollback()`.

No autonomous Evolution promotion is being claimed by this milestone.

## 5. Tests added/extended

The Android test suite now explicitly checks that:

- USER facts and consolidated knowledge survive heavy recent-memory noise;
- only returned cognitive memories are marked recalled;
- exact textual duplicates can be superseded conservatively;
- USER facts are never automatically superseded by duplicate cleanup;
- transient visual retention remains bounded by compiled policy;
- repeated operational Shared State snapshots compact to one latest semantic state per origin/kind/entity;
- rare Night Learning events survive large amounts of operational snapshot churn;
- different origins remain distinct;
- non-operational event history remains event-ID based;
- Evolution confidence does not automatically pass at the minimum trial size;
- Evolution can compare genuine improvements above the UI score ceiling.

## What 0.1.20.1 proves

If CI passes, this milestone proves that several existing cognitive signals now have a coherent producer-to-consumer path and that previously neutralized guard rails are mechanically meaningful.

It does **not** prove that Jade can generate a new capability by herself.

## Next falsifiable milestone — 0.1.21

0.1.21 must end in a causal behavioral proof rather than another displayed learning object:

```text
new deterministic task family
 -> gap detected
 -> LearningGoal
 -> TRAIN / VALIDATION examples
 -> external teacher proposes bounded SkillSpec
 -> restricted Procedure Runtime executes candidates
 -> candidate frozen by spec_sha256
 -> single-use hidden SEALED_TEST
 -> retain approved skill
 -> full process restart
 -> recognize matching unseen task
 -> retrieve retained skill
 -> execute locally
 -> correct result
 -> no teaching-LLM call to solve the final task
```

Until that complete chain passes, Jade should still be described as having learning/evaluation infrastructure and bounded adaptation rather than autonomous general skill acquisition.
