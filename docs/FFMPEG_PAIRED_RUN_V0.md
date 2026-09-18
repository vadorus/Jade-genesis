# Jade Genesis — Manual Paired FFmpeg Run V0

## Purpose

Jade can now run the same bounded FFmpeg probe on two explicitly selected
compatible nodes and compare the resulting verified evidence.

This is the first real bridge from "shadow policy" to "measured execution".

## Flow

```text
human / explicit caller chooses node A and node B
  -> Jade refreshes known nodes
  -> verifies both advertise ffmpeg_transcode_probe_v1
  -> runs the fixed synthetic probe on node A
  -> records verified evidence
  -> runs the same fixed probe on node B
  -> records verified evidence
  -> paired comparator
  -> success-first comparison
  -> latency comparison only if both verify
```

## Why explicit nodes

V0 intentionally does not let Jade silently pick a challenger and promote it.

The caller must provide two different node ids:

- incumbent node;
- challenger node.

The coordinator rejects:

- blank ids;
- the same node twice;
- missing nodes;
- offline/error nodes;
- nodes without `task_execution_v3`;
- nodes without `ffmpeg_transcode_probe_v1`.

## No fallback between nodes

The fixed FFmpeg probe can now be routed to one explicit node.

When an explicit node is requested, TaskRouter filters to that one node only.
If it cannot run there, the measurement fails. Jade does not silently execute
on another node, because that would corrupt the comparison.

Normal task routing remains unchanged when no explicit node is requested.

## Evidence

Each side creates a normal `CapabilityExecutionEvidence` record.

The paired comparator then reports:

- verification state of both executions;
- duration of both executions;
- whether latency is comparable;
- latency delta;
- faster node id only when both passed verification.

## Important limitation

One paired run is not enough to decide that one node is generally better.

Performance can vary because of:

- current CPU/GPU load;
- thermal state;
- other tasks;
- network conditions;
- runtime startup/cache effects.

V0 therefore leaves:

```text
automaticPromotionAllowed = false
```

## Next gate

The next meaningful step is repeated paired trials with simple statistics:

```text
A/B paired probe repeated N times
  -> verified success rate
  -> median latency
  -> p95 / spread
  -> noise estimate
  -> only then can a canary recommendation be considered
```

That is where Jade starts learning which node is consistently better for a
specific capability instead of reacting to one lucky measurement.
