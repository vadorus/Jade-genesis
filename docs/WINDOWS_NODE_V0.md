# Jade Node for Windows V0

## Scope

Windows V0 packages and supervises the already allow-listed Jade Node Runtime.
It does not change the Android application version, protocol, paired identity,
task allow-list, Skill proof policy or VPS service.

## Trust and network boundaries

- Android already accepts only `100.64.0.0/10` or `*.ts.net` node routes.
- The launcher discovers a local `100.64.0.0/10` address from active Windows
  interfaces and passes it through the new runtime `--bind-address` option.
- The runtime therefore listens on Tailscale rather than `0.0.0.0` when managed
  by Jade Node.
- If the Tailscale address disappears, the managed runtime is stopped. If the
  address changes, the runtime is restarted on the new address.
- The firewall rule is limited by program, TCP port and Tailscale source range.
- The existing authenticated `X-Jade-Token` protocol remains mandatory.
- Pairing secrets are never written to logs or committed. They are put on the
  clipboard only after an explicit local button press.

The core runtime keeps its historical default bind address for compatibility
with existing Linux/VPS deployments. The Windows launcher always supplies an
explicit address.

## Process model

`JadeNode.exe` contains `JadeNodeRuntime.exe` as a SHA-256-pinned resource. The
launcher extracts it to a stable per-user path, starts it without a console,
probes authenticated health every three seconds and uses bounded exponential
restart delay. An existing healthy runtime with the same persisted identity,
token and explicitly advertised Tailscale-only bind can be used until it exits;
an older broad listener is rejected and the packaged runtime then takes over
after that old process is closed.

Ollama is probed on loopback. If it is installed but unavailable, the launcher
may start only the fixed local command `ollama.exe serve`. No arbitrary command,
model download or paid provider is introduced.

## Update model

Stable update discovery is pinned to the official GitHub repository and exact
asset name. Drafts/prereleases, foreign URLs, missing GitHub SHA-256 digests,
oversized assets and hash mismatches fail closed. Application happens only with
zero active runtime tasks. The new executable replaces the old one from a
separate process and preserves config/state.

This V0 provides transport/repository pinning and SHA-256 integrity, not
Authenticode publisher identity. A public stable Windows release remains gated
on adding a protected code-signing certificate to the tagged release workflow.

## Failure behavior

- Tailscale offline: runtime stopped; tray reports the required action.
- Runtime crash/unhealthy: bounded automatic restart.
- Ollama absent: node remains usable for non-brain allow-listed tasks.
- FFmpeg absent: bounded FFmpeg capability is reported unavailable.
- Firewall elevation declined: no broad fallback rule is created.
- Update verification failure: current version continues running.
- Launcher exit: only child processes started by the launcher are terminated.
