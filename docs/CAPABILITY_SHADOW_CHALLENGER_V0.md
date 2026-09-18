# Jade Genesis — Capability Shadow Challenger V0

## Purpose

This step introduces the first **manually-authored challenger policy** for the
Capability Replay Lab.

The challenger is evaluated in **shadow only**. It can compare a different
free-provider choice against the incumbent decision, but it cannot execute the
provider or change production routing.

This is still measurement, not autonomous improvement.

## Flow

```text
CapabilityDecisionTrace
  -> deterministic A/A replay
  -> Capability Branch Harvester
  -> manual challenger policy
  -> shadow comparison
```

The manual challenger declares:

- a stable policy id;
- one operation;
- one preferred provider id;
- optionally one preferred node id.

Example:

```text
operation = media_transcode
preferred_provider = ffmpeg-local
preferred_node = pc-b
```

The policy can only choose a provider/node pair that was already present in the
original trace as an observed, available, policy-safe counterfactual.

## Hard boundaries

Shadow V0 cannot:

- execute FFmpeg, Blender, ComfyUI, Ollama or any other provider;
- install software;
- call a plugin or paid API;
- buy credits or start a subscription;
- invent a provider or node that was absent from the trace;
- change the production Capability Registry;
- change TaskRouter or JadeConfig;
- promote the challenger;
- access SEALED_TEST;
- modify model weights;
- rewrite source code.

Even if a historical trace says paid providers were allowed, this V0 shadow
lab rejects `PAID` challengers. Only `LOCAL_FREE` and `CLOUD_FREE` may be
compared.

## Missing coverage behavior

If the requested challenger provider/node was not observed in the original
decision, Shadow V0 does **not** guess.

It preserves the incumbent choice and records:

```text
targetObserved = false
changed = false
```

That means "insufficient counterfactual evidence", not "the incumbent is
better".

## Report

The report records:

- total traces;
- applicable traces for the requested operation;
- traces skipped because they belong to another operation;
- traces where the requested challenger was actually observed;
- traces where the challenger was missing;
- changed choices;
- unchanged choices;
- change rate across applicable traces.

`hasComparableEvidence` becomes true only when at least one applicable trace
contains the requested observed challenger.

## JadeCore surface

JadeCore exposes:

- `harvestRecentCapabilityBranches(limit)`;
- `shadowRecentCapabilityDecisions(...)`.

The shadow call requires the caller to supply the manual challenger. Jade does
not generate one automatically in V0.

## What this proves

This milestone can prove that Jade is able to:

1. record a real capability decision;
2. reproduce the incumbent decision offline;
3. identify real observed counterfactuals;
4. apply a manually specified alternative policy offline;
5. report whether that policy would have changed the decision;
6. do all of this without touching production execution.

It does **not** prove that the challenger produces a better real-world result.

## Next gate

The next meaningful step is not auto-promotion. It is outcome instrumentation
for one bounded free capability family, preferably FFmpeg:

```text
shadow challenger
  -> bounded real execution adapter
  -> deterministic result verifier
  -> latency/resource/outcome evidence
  -> incumbent vs challenger comparison
  -> controlled canary
  -> rollback/control reproduction
```

Only after the measurement path predicts real behavior reliably should Jade
generate or promote policies automatically.
