# Jade Genesis 0.1.18 — Adaptive Strategy Registry

## Goal

0.1.18 introduces the first durable adaptive substrate owned by Jade Genesis.

0.1.17 could transform explicit outcomes into bounded Night Learning strategy candidates, but those candidates were transient. 0.1.18 makes safe `STRATEGY_HINT` evidence durable so Jade can accumulate experience across night cycles without pretending that an external LLM is Jade's identity or automatically changing live behavior.

The new chain is:

`experience -> outcome -> Runtime Eval -> Night Learning -> STRATEGY_HINT -> durable strategy registry -> sandbox evaluation -> explicit promotion -> rollback`

## What is persisted

`node-agent/adaptive_strategy_registry.py` stores an identity-bound, atomic JSON registry with backup recovery. Each learned strategy can carry:

- a stable strategy id derived from the logical strategy rather than one night-cycle id;
- target and structured task/profile/model/node scope;
- title and bounded rationale;
- provenance from Night Learning candidate and experiment ids;
- repeated evidence count;
- sandbox champion/challenger scores;
- confidence, sample count and protected-failure count;
- lifecycle status;
- previous active strategy id for rollback.

Repeated equivalent Night Learning candidates reinforce the same logical registry entry instead of creating an unbounded duplicate list.

## Lifecycle

Supported lifecycle states are:

`CANDIDATE -> TESTING -> VALIDATED -> ACTIVE`

An active strategy can later become `RETIRED` when an explicitly approved successor is activated. A successor can be explicitly rolled back to `ROLLED_BACK`, restoring the previous active strategy when available.

A strategy is only marked `VALIDATED` when all current minimum sandbox gates are met:

- at least 5 paired samples;
- challenger delta at least +0.25;
- confidence at least 0.75;
- zero protected failures.

These are registry validation gates, not permission to activate the strategy.

## Promotion and rollback gates

Persistence of evidence is automatic because storing experience is the purpose of the registry.

Activation is not automatic.

`promote()` requires:

1. an existing `VALIDATED` strategy;
2. an explicit approval boolean set to `True` by a trusted caller.

`rollback()` likewise requires explicit approval.

The Night Cycle never calls either operation.

## Night Cycle integration

`node-agent/adaptive_vps_night_cycle.py` wraps the already validated 0.1.17 VPS Night Cycle instead of rewriting it.

After the normal bounded cycle completes, the wrapper:

1. reads the just-published `vps_learning_snapshot`;
2. keeps only safe `STRATEGY_HINT` candidates for the allow-listed target `routing.profile_model_preference`;
3. derives structured task/profile/model/node scope from existing signal -> hypothesis -> experiment links when the candidate did not already carry scope;
4. persists the candidate into the adaptive registry;
5. publishes a bounded registry summary through the existing allow-listed `vps_maintenance_snapshot` channel with entity id `adaptive-strategy-registry`.

This keeps Shared Genesis State compatibility while exposing the learned substrate to the next Pixel synchronization.

## Explicit non-goals and invariants

0.1.18 does **not** claim general intelligence, model training, or autonomous code evolution.

The registry cannot:

- automatically activate a strategy;
- automatically promote a strategy;
- rewrite production code;
- mutate model weights;
- execute shell commands;
- lower SafetyPolicy thresholds;
- store raw conversation text;
- treat personal user feedback as external factual truth.

External LLMs remain tools/providers. The registry belongs to Jade's own persistent state and is model-independent.

## Android version

The Android product version is bumped to:

- `versionName = 0.1.18`
- `versionCode = 35`

The Node Runtime wire version remains `0.1.6` / `jade-genesis-node/0.0.6` because this change does not require a protocol break.

## Verification focus

CI must prove:

- registry files compile without third-party Python dependencies;
- identity binding and persistence work;
- equivalent evidence is coalesced;
- unsafe candidates are ignored;
- sandbox validation gates are enforced;
- promotion and rollback require explicit approval;
- rollback restores a previous active strategy when possible;
- Night Cycle persists strategies but performs no activation/promotion;
- health/runtime expose `adaptive_strategy_registry_v1`;
- Android remains buildable as 0.1.18 / 35;
- tracked repository sources are unchanged by validation.
