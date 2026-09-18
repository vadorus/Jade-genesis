# Jade Genesis — FFmpeg Execution Evidence V0

## Purpose

This milestone closes the first Android-side evidence loop for a real free
capability.

The Node Runtime already exposes the bounded
`ffmpeg_transcode_probe_v1`. Android can now route that task to an eligible
node, validate the returned proof, and persist a content-free execution record.

## Flow

```text
Pixel JadeCore
  -> TaskRouter
  -> node with ffmpeg_transcode_probe_v1
  -> bounded FFmpeg synthetic transcode
  -> ffprobe machine verification
  -> TaskRouter validation
  -> CapabilityExecutionEvidence
  -> bounded local evidence store
```

## Evidence fields

The persisted record contains only measurement data:

- task id and task kind;
- provider id and operation;
- executed node id/name;
- success / verification status;
- end-to-end duration observed by Android;
- node-local execution duration reported by the bounded probe;
- output byte count;
- output SHA-256;
- codec and dimensions;
- fallback-used flag;
- start/completion timestamps.

It does **not** persist the task payload, media bytes, FFmpeg stdout/stderr, user
files or arbitrary tool output.

## Android surface

`JadeCore` now exposes:

- `runFfmpegCapabilityProbe()`;
- `recentCapabilityExecutionEvidence(limit)`.

The probe is still fixed and synthetic. The caller supplies no file path or
FFmpeg arguments.

## Routing boundary

The TaskRouter request requires:

```text
ffmpeg_transcode_probe_v1
```

so the Pixel cannot accidentally execute it locally. Only an online/local node
that explicitly advertises the bounded runtime capability can be selected.

## Validation boundary

Android verifies the returned JSON before marking the distributed
task successful:

- provider must be `ffmpeg-local`;
- operation must be `media_transcode_probe`;
- runtime success and verification must both be true;
- user-file access, arbitrary arguments, shell execution, network input and
  persistent output must all be false;
- codec must be MPEG-4;
- dimensions must be exactly 160×90;
- output must be non-empty;
- `metrics.duration_ms` must be present and non-negative;
- SHA-256 must be a valid lowercase 64-hex digest.

## What this proves

Jade now has a path from a real node capability to persistent measured evidence,
without a paid API and without storing user content.

Comparison decisions use the node-local execution duration, not the Pixel-to-node transport time. The end-to-end duration is retained separately for UX and network analysis.

This still does **not** establish that one node/provider is better than another.

## Next gate

The next step is paired comparison:

```text
same bounded probe
  -> incumbent node
  -> challenger node
  -> verified evidence from both
  -> compare success first
  -> compare latency only when both pass
  -> no automatic promotion
```

That paired result will become the first causal bridge between Replay Lab shadow
choices and real execution performance.
