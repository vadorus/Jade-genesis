# Jade Genesis — Capability DecisionTrace V0

## Purpose

Jade can now discover free/local providers, but discovery alone is not enough
to improve routing safely. Capability DecisionTrace records **why a provider
was selectable and which provider Jade chose**, without executing the provider.

This is the measurement step before any challenger policy or Branch Harvester.

## Flow

```text
current GenesisNode health
  -> NodeCapabilityBridge
  -> discovered LOCAL_FREE providers
  -> CapabilitySelectionCoordinator
  -> CapabilityRegistry free-first policy
  -> selected provider + node (or abstention)
  -> CapabilityDecisionTrace
  -> Capability A/A Replay
```

## Recorded fields

A trace records:

- requested operation;
- policy name and free/paid flags;
- each discovered provider candidate supporting that operation;
- provider id and node id;
- provider type and monetary cost class;
- availability and eligibility;
- network requirement;
- declared operations;
- selected provider/node, or explicit no-selection;
- decision timestamps.

It does **not** store user prompts, task payloads, media, model output or tool
output.

## Policy boundary

The default coordinator uses `CapabilitySelectionPolicy.freeOnly()`:

```text
LOCAL_FREE
  -> CLOUD_FREE fallback allowed by policy
  -> PAID disabled
```

The current node discovery bridge advertises only allow-listed
`LOCAL_FREE` providers. Unknown `local_free:` markers are ignored
fail-closed.

## A/A replay

`CapabilityReplayLab` independently recomputes provider eligibility and the
free-first ordering from the recorded context. It does not trust the recorded
`eligible` flag to decide the winner and never executes a tool.

A replay match requires both:

```text
provider_id matches
AND
node_id matches
```

An abstention is also replayable: if no eligible provider existed and no
provider was recorded as selected, A/A reproduces that decision.

## JadeCore surface

JadeCore now exposes:

- `discoveredFreeCapabilities()`;
- `selectFreeCapability(operation)`;
- `recentCapabilityDecisionTraces()`;
- `replayRecentCapabilityDecisions()`.

`selectFreeCapability` performs **selection only**. It does not start Blender,
ComfyUI, FFmpeg, Ollama or any other provider.

## What this proves

V0 can prove that Jade's capability-selection context is sufficiently captured
to reproduce the same free-first choice offline.

It does not yet prove that the chosen provider completed a real task better than
another provider.

## Next gate

Before changing policy automatically:

```text
A/A replay stable
  -> real execution adapter for one bounded capability
  -> outcome / latency / resource evidence
  -> manually authored challenger policy
  -> offline replay
  -> shadow
  -> canary
  -> explicit promotion / rollback
```

The first execution family should remain narrow and cheap. FFmpeg is a good
candidate because its results can be verified deterministically without paid
services.
