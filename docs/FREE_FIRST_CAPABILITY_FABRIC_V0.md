# Jade Genesis — Free-First Capability Fabric V0

## Goal

Jade must be able to discover and choose useful capabilities without creating a
hidden financial dependency.

The default policy is therefore:

```text
LOCAL_FREE
   ↓
CLOUD_FREE (optional fallback)
   ↓
PAID (disabled)
```

A model, plugin or API being technically reachable does not make it eligible
for automatic use.

## V0 invariants

1. **No paid provider is selected by default.**
2. **No component in this layer purchases credits or starts a subscription.**
3. **Local/free capability is preferred when it can satisfy the task.**
4. **Cloud-free fallback may be used only when explicitly enabled and still
   free at execution time.**
5. **Availability comes from real node discovery/probing.**
6. **The static catalog is an allow-list of adapters Jade may discover; it is
   not evidence that software is installed.**
7. **Capability selection is N1 orchestration. It does not modify source code,
   model weights, verifier rules or SEALED_TEST data.**

## Initial local/free adapters

The Android-side catalog declares these eligible providers:

| Provider | Initial capability families |
| --- | --- |
| Ollama | local text generation / reasoning |
| ComfyUI | image generation workflows |
| whisper.cpp | speech-to-text / transcription |
| Piper | text-to-speech |
| Blender | 3D modelling / rendering / animation |
| FFmpeg | media conversion / assembly |
| Krita | image editing / painting |
| Playwright | browser automation |

These are candidates for node adapters. They remain `available=false` until a
real node probe proves otherwise.

## Why Higgsfield is not core infrastructure

Higgsfield is useful as an orchestration reference and can expose many
capabilities through a plugin, but its normal operation is credit-metered.
Jade's core must not depend on that service for a capability that can be
provided locally for free.

A future external provider may be represented in the registry as
`CLOUD_FREE` or `PAID`, but the default policy will still reject
`PAID`.

## Selection model

A capability descriptor records:

- stable provider id;
- display name;
- supported operations;
- provider type;
- monetary access class;
- current availability;
- node id when applicable;
- network requirement;
- human-readable details.

The registry chooses only among **available + allowed** providers.

Within the default policy:

1. `LOCAL_FREE` ranks first;
2. `CLOUD_FREE` ranks second;
3. `PAID` is excluded.

This is intentionally simpler than the future Replay Lab. V0 establishes the
policy boundary first.

## Next implementation step

The next code step is **real discovery**, not automatic installation:

```text
Node probe
  -> detect Ollama / ComfyUI / whisper.cpp / Piper / Blender / FFmpeg /
     Krita / Playwright
  -> report version + node + availability
  -> CapabilityRegistry
  -> DecisionTrace records what Jade could choose and what it selected
```

Once DecisionTrace exists, Replay Lab can compare alternative routing policies
without changing production behavior.

## Relationship to existing Jade architecture

This layer sits above raw node capability strings and below Task Router policy:

```text
NodeManager / local probes
        ↓
Capability Registry
        ↓
free-first policy
        ↓
Task Router / Workflow Planner
        ↓
selected node + tool/model/Skill
```

The current `GenesisNode.capabilities` list remains valid. V0 does not replace
it; it introduces a typed registry that can eventually unify node, model, tool
and Skill capabilities under one selection policy.

## Explicitly out of scope for V0

- automatic package installation;
- automatic paid API activation;
- credit purchasing;
- subscriptions;
- self-modifying adapters;
- automatic N3 code changes;
- model fine-tuning / LoRA / RL;
- autonomous policy promotion.

Those require separate proof and governance work.
