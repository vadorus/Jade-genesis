# Jade Genesis — FFmpeg Paired Comparison V0

## Purpose

This milestone gives Jade its first deterministic comparison between two real
execution evidences for the same free capability.

It does **not** choose a winner for production and does not promote anything.
It only answers a narrower question:

```text
Did both executions pass machine verification, and if so, which node was faster?
```

## Evidence order

The comparison is intentionally lexicographic:

1. machine-verified success first;
2. latency only when both executions passed verification.

A failed or unverified execution can never be declared better simply because it
finished faster.

## Inputs

Both evidences must have:

- the same provider id;
- the same operation;
- different node ids.

For V0 the intended pair is:

```text
provider = ffmpeg-local
operation = media_transcode_probe
```

## Status values

`BOTH_VERIFIED`

Both executions passed machine verification. Latency may be compared.

`INCUMBENT_ONLY_VERIFIED`

Only the incumbent passed. Latency is not compared.

`CHALLENGER_ONLY_VERIFIED`

Only the challenger passed. Latency is not compared.

`NEITHER_VERIFIED`

Neither passed. Latency is not compared.

## Latency metric

When both executions are verified:

```text
latency_delta_ms = challenger_duration_ms - incumbent_duration_ms
```

Negative means the challenger was faster for that one measured execution.
Positive means the challenger was slower.

This is measurement evidence, not a promotion decision.

## No automatic promotion

Every comparison contains:

```text
automaticPromotionAllowed = false
```

The V0 lab cannot mutate:

- Capability Registry;
- TaskRouter policy;
- JadeConfig;
- Node priorities;
- Skill routes;
- model weights;
- source code.

## JadeCore surface

JadeCore exposes:

```text
compareLatestFfmpegCapabilityEvidence(
    incumbentNodeId,
    challengerNodeId,
    limit
)
```

It uses the most recent stored FFmpeg execution evidence for each node.

If either node has no matching evidence, the result is `null`. Jade does not
invent missing measurements.

## Why this matters

This is the first step where Replay Lab decisions can eventually be checked
against real execution data:

```text
shadow says "try node B"
      ↓
bounded execution on A and B
      ↓
both results machine-verified
      ↓
paired comparison
      ↓
later: repeated trials / canary / rollback proof
```

One pair is not enough to change policy. Repeated evidence is required before
the project should consider any routing promotion.

## Next gate

The next implementation step is a bounded paired-run coordinator that can run
the fixed FFmpeg probe on two explicitly selected compatible nodes, collect both
evidences, and feed this comparator.

That coordinator must remain manual/shadow-only in V0 and must not auto-promote.
