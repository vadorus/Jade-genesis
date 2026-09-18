# Jade Genesis — A/A Replay V0

## Purpose

A/A Replay is the first validation gate for Replay Lab.

Before Jade compares a challenger routing policy against production, the replay
instrument must prove that the **current policy can reproduce its own recorded
decision** from the captured DecisionTrace.

This is an instrumentation test, not self-improvement.

## Input

A/A Replay consumes only DecisionTrace records.

It does not execute the original task, call a model, contact a node, or inspect
the task payload/output.

The replayed ordering is:

```text
eligible
  -> routing score descending
  -> RAM available descending
  -> CPU cores descending
  -> original alternative order
```

That matches the current TaskRouter winner ordering captured by DecisionTrace.

## Pass condition

For every replayable trace in a validation batch:

```text
replayed winner == recorded winner
```

A missing replay candidate for a trace that recorded a winner is a failure.

An empty dataset does not count as a successful A/A validation.

## Why this comes before Branch Harvester

If Jade cannot reproduce an incumbent decision from its own trace, then a later
challenger comparison is ambiguous. We would not know whether a difference came
from the challenger policy or from incomplete replay state.

The intended sequence remains:

```text
DecisionTrace
  -> A/A Replay
  -> Branch Harvester
  -> manual challenger
  -> shadow evaluation
  -> canary
  -> rollback/control
  -> automated policy proposals
```

## Limits of V0

A/A Replay V0 reuses the **captured final routing score**. It does not yet
recompute that score from historical task evidence.

That is intentional for the first replay gate. A later Replay V1 can capture
the raw score inputs and recompute the complete routing formula independently.
