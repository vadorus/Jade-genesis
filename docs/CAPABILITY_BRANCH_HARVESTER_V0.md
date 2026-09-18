# Jade Genesis — Capability Branch Harvester V0

## Purpose

Branch Harvester is the next Replay Lab instrument after deterministic A/A
replay.

A/A proves that Jade can reproduce the incumbent capability decision from the
recorded context. Branch Harvester asks a narrower question:

> Which other policy-safe providers were actually visible at that same decision
> point?

It does **not** execute those alternatives.

## V0 rule

A harvested branch must come from the original
`CapabilityDecisionTrace.alternatives`.

V0 never invents a provider, node, model, tool or availability state.

A branch is harvestable only when the recorded alternative:

- was available;
- supported the recorded operation;
- was allowed by the recorded monetary policy;
- was not the exact incumbent provider + node pair.

## Safety boundary

The harvester cannot:

- execute a provider;
- call a model;
- call a plugin;
- install software;
- activate a paid API;
- mutate the Capability Registry;
- mutate JadeConfig;
- promote a challenger;
- access SEALED_TEST;
- write model weights or source code.

This keeps Branch Harvester in the N1 measurement layer.

## Coverage

The report records:

- total traces;
- traces with at least one real counterfactual;
- traces without a counterfactual;
- number of harvested branches;
- counterfactual coverage rate.

An empty dataset reports zero coverage.

Low coverage is not a failure of the incumbent policy. It means Jade does not
yet have enough observed alternatives to compare routing choices causally.

## Replay Lab sequence

```text
DecisionTrace
  -> deterministic A/A replay
  -> Capability Branch Harvester V0
  -> manually-authored challenger policy
  -> shadow comparison
  -> bounded canary
  -> causal rollback/control
  -> only then automated policy proposals
```

The next step after this V0 is a **manual challenger**, not autonomous policy
generation.
