# Jade Genesis — Repeated Paired FFmpeg Trials V0

## Purpose

One fast execution can be luck. V0 now lets Jade repeat the same bounded FFmpeg
comparison several times before treating the measurements as meaningful.

## Flow

```text
node A + node B
  -> paired bounded probe
  -> repeat 2..7 rounds
  -> machine-verified evidence each round
  -> aggregate statistics
```

## Statistics

The report includes:

- requested/completed rounds;
- verified rounds for each node;
- rounds where both sides verified;
- comparable-latency rounds;
- faster-round counts and ties;
- median latency per node;
- p95 latency per node;
- median challenger-minus-incumbent latency delta.

Latency from an unverified execution is excluded.

## Boundaries

- minimum 2 rounds;
- maximum 7 rounds;
- fixed synthetic FFmpeg profile only;
- same two explicit nodes for the full series;
- no fallback to another node;
- no paid API;
- no automatic routing change;
- no automatic promotion.

```text
automaticPromotionAllowed = false
```

## Why this matters

Jade is starting to build evidence about which machine consistently performs a
specific capability better, rather than reacting to one lucky run.

This mechanism is intended to generalize later to other free capabilities such
as Ollama, ComfyUI, Blender and local speech tools.

## Next gate

After repeated measurements are stable, the next stage is a bounded canary
recommendation layer. It may suggest a temporary routing preference, but it must
still require explicit approval and keep rollback/control evidence.
