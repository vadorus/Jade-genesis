# Jade Genesis — Bounded FFmpeg Capability Probe V0

## Purpose

This milestone is the first bounded **real execution adapter** for the
Free-First Capability Fabric.

The goal is not to expose general FFmpeg control. The goal is to give Jade one
small, deterministic, machine-verifiable operation that can produce real
latency/outcome evidence without paid services.

## Operation

Provider:

```text
ffmpeg-local
```

Capability operation:

```text
media_transcode_probe
```

Node Runtime task:

```text
ffmpeg_transcode_probe_v1
```

The probe generates a tiny synthetic video internally using FFmpeg's `lavfi`
`testsrc2` source, encodes it to a temporary MPEG-4/MP4 file, verifies it
with `ffprobe`, records bounded outcome metrics and deletes the temporary
output.

No user media is required.

## Fixed profile

V0 uses one compiled profile only:

```text
ffmpeg_synthetic_mpeg4_mp4_v1
160x90
10 fps
0.5 seconds
video only
MPEG-4 in MP4
single FFmpeg thread
```

The caller cannot provide arbitrary FFmpeg arguments.

## Machine verification

A successful probe must satisfy all of the following:

- FFmpeg completed successfully;
- a non-empty bounded output file was produced;
- FFprobe completed successfully;
- video codec is `mpeg4`;
- dimensions are exactly 160×90;
- reported duration is inside the bounded expected interval;
- an output SHA-256 is produced;
- the temporary output is deleted after measurement.

The result records execution duration and output size/hash.

The hash is evidence for one execution artifact, not a cross-machine golden
hash. Different FFmpeg builds may produce different valid byte streams.

## Security boundary

The V0 adapter cannot:

- read a user-supplied file path;
- write to a user-selected path;
- accept arbitrary FFmpeg flags;
- execute through a shell;
- use network media inputs;
- persist its generated output;
- call a paid service;
- install FFmpeg;
- promote a routing policy;
- modify source code or model weights.

The request body accepts only the fixed `profile` field. Unknown fields,
oversized payloads and unsupported profiles fail closed.

Subprocess execution uses an argument array with `shell=False`.

## Discovery

The existing free capability discovery still detects `ffmpeg-local`.

When both `ffmpeg` and `ffprobe` are present, Node Runtime additionally
reports:

```text
ffmpeg_transcode_probe_v1
```

and exposes bounded probe readiness metadata.

Android's local free catalog now also declares `media_transcode_probe` for
`ffmpeg-local`.

## Runtime version

This capability increments the Node Runtime wrapper to **0.1.9**.

Wire protocol compatibility remains:

```text
jade-genesis-node/0.0.6
```

## CI proof

Node Runtime CI now contains two levels of verification:

1. dependency-free unit tests with injected fake executables;
2. one real bounded FFmpeg/FFprobe execution on the GitHub Actions runner.

The real CI execution verifies the generated media result and records duration,
size and SHA-256 in the job log.

## Relationship to Replay Lab

This adapter creates the missing bridge from:

```text
"Jade would choose provider B in shadow"
```

to:

```text
"provider B actually executed a bounded task and produced verified outcome evidence"
```

It does not yet compare two nodes automatically.

## Next gate

After this adapter is stable:

```text
Capability DecisionTrace
  -> Branch Harvester
  -> manual shadow challenger
  -> bounded FFmpeg execution on candidate node
  -> verified outcome + latency evidence
  -> paired incumbent/challenger comparison
  -> controlled canary
  -> rollback/control reproduction
```

The paired comparison must remain explicit and bounded before any automatic
promotion is considered.
