# Jade Node for Windows V0

Jade Node turns the existing dependency-free Python Node Runtime into one
self-contained `JadeNode.exe` for Windows 10/11 x64.

## User experience

The first double-click:

1. copies the executable to `%LOCALAPPDATA%\Jade Genesis\Node`;
2. registers a per-user Windows startup entry (no administrator rights);
3. extracts the exact embedded Node Runtime after verifying its SHA-256;
4. detects the active Tailscale IPv4 address;
5. starts the runtime bound to that address only;
6. starts an installed local `ollama serve` when Ollama is not already running;
7. supervises health and restarts a failed runtime with bounded backoff;
8. exposes status and pairing information from a tray application.

The only elevation request creates one inbound Windows Firewall rule scoped to:

- `JadeNodeRuntime.exe`;
- the configured Jade TCP port (`8765` by default);
- source addresses in the Tailscale CGNAT range `100.64.0.0/10`.

No router port is opened. The launcher never accepts a user-provided command or
exposes arbitrary shell execution.

## Identity and optional local tools

The existing `%USERPROFILE%\.jade-genesis\node-agent.json` remains the source of
truth. An existing `node_id`, token, name and non-default port are preserved.

Tailscale is required. Ollama and FFmpeg are optional local/free capabilities.
Jade Node detects and starts an installed Ollama service, but it does not install
Tailscale, download models, install FFmpeg or use a paid API.

## Updates

The Windows application checks stable GitHub releases from the exact repository
`vadorus/Jade-genesis`. It accepts only tags named `jade-node-v*`, the exact
asset `JadeNode.exe`, an HTTPS download URL under that repository, a bounded
file size and the SHA-256 digest returned by GitHub. The downloaded executable
is hash-verified before replacement and is applied only while no Jade task is
active.

The CI artifact is not Authenticode-signed yet. A stable public Windows release
must add a real code-signing certificate before this V0 should be presented as a
signed installer.

## Build

`.github/workflows/jade-node-windows-ci.yml` builds on Windows:

- PyInstaller packages `node-agent/jade_node_agent.py` as the embedded runtime;
- the packaged runtime is started and probed on loopback;
- dependency-free .NET core tests validate Tailscale, config, protocol and
  update boundaries;
- .NET 8 publishes one self-contained `JadeNode.exe`;
- the final executable runs an embedded-payload self-test;
- the workflow uploads the executable, installation notes and SHA-256.

Pushing an explicit `jade-node-v<project-version>` tag creates the corresponding
GitHub release. No tag is created by normal branch/PR CI.
