using System.Diagnostics;
using System.Net.Http.Headers;
using JadeNode.Core;

namespace JadeNode.Windows;

internal sealed class NodeSupervisor : IAsyncDisposable
{
    private readonly HttpClient _httpClient = new()
    {
        Timeout = TimeSpan.FromSeconds(3),
    };
    private readonly object _processLock = new();
    private readonly object _logLock = new();
    private CancellationTokenSource? _cancellation;
    private Task? _loopTask;
    private Process? _runtimeProcess;
    private Process? _ollamaProcess;
    private string? _runtimeBindAddress;
    private DateTimeOffset _restartNotBefore = DateTimeOffset.MinValue;
    private DateTimeOffset _nextOllamaProbe = DateTimeOffset.MinValue;
    private int _restartFailures;
    private int _healthFailures;
    private bool _lastProbeRejectedBinding;
    private bool _desiredRunning;

    public NodeSupervisor()
    {
        _httpClient.DefaultRequestHeaders.UserAgent.Add(
            new ProductInfoHeaderValue("JadeNode", "0.1"));
    }

    public event EventHandler<NodeStatusSnapshot>? StatusChanged;

    public NodeStatusSnapshot Current { get; private set; } = NodeStatusSnapshot.Initial;

    public Task StartAsync()
    {
        if (_loopTask is not null)
        {
            return Task.CompletedTask;
        }

        PayloadInstaller.EnsureRuntime();
        _desiredRunning = true;
        _cancellation = new CancellationTokenSource();
        _loopTask = Task.Run(() => RunLoopAsync(_cancellation.Token));
        return Task.CompletedTask;
    }

    public async Task RestartAsync()
    {
        _restartFailures = 0;
        _healthFailures = 0;
        _restartNotBefore = DateTimeOffset.MinValue;
        StopRuntime();
        Publish(Current with
        {
            State = NodeRunState.Starting,
            Message = "Redémarrage du runtime…",
            ManagedRuntime = false,
        });
        await Task.Delay(250).ConfigureAwait(false);
    }

    public async Task StopAsync()
    {
        _desiredRunning = false;
        var cancellation = _cancellation;
        var loopTask = _loopTask;
        _cancellation = null;
        _loopTask = null;
        cancellation?.Cancel();
        if (loopTask is not null)
        {
            try
            {
                await loopTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // Expected during normal shutdown.
            }
        }

        cancellation?.Dispose();
        StopRuntime();
        StopOllama();
        Publish(new NodeStatusSnapshot(NodeRunState.Stopped, "Jade Node est arrêté."));
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        _httpClient.Dispose();
    }

    private async Task RunLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _desiredRunning)
        {
            try
            {
                await SuperviseOnceAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                Log(AppPaths.SupervisorLog, $"Supervisor error: {exception.GetType().Name}: {exception.Message}");
                Publish(Current with
                {
                    State = NodeRunState.Error,
                    Message = "Erreur de supervision. Nouvelle tentative automatique…",
                });
            }

            await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task SuperviseOnceAsync(CancellationToken cancellationToken)
    {
        var tailscaleIp = TailscaleDiscovery.FindIpv4();
        if (tailscaleIp is null)
        {
            StopRuntime();
            Publish(new NodeStatusSnapshot(
                NodeRunState.WaitingForTailscale,
                "Tailscale est hors ligne. Connecte Tailscale pour rendre ce PC visible.",
                Port: ReadPort()));
            return;
        }

        if (_runtimeBindAddress is not null
            && !string.Equals(_runtimeBindAddress, tailscaleIp, StringComparison.Ordinal)
            && HasManagedRuntime())
        {
            Log(AppPaths.SupervisorLog, $"Tailscale address changed to {tailscaleIp}; restarting runtime.");
            StopRuntime();
        }

        await EnsureOllamaAsync(cancellationToken).ConfigureAwait(false);

        var hasConfig = NodeAgentConfig.TryLoad(AppPaths.NodeConfigPath, out var config, out var configError);
        var port = config?.Port ?? NodeAgentConfig.DefaultPort;
        if (hasConfig && config is not null)
        {
            var health = await ProbeHealthAsync(tailscaleIp, config, cancellationToken).ConfigureAwait(false);
            if (health is not null)
            {
                _healthFailures = 0;
                _restartFailures = 0;
                _restartNotBefore = DateTimeOffset.MinValue;
                Publish(new NodeStatusSnapshot(
                    NodeRunState.Online,
                    HasManagedRuntime() ? "PC prêt pour Jade." : "Runtime Jade déjà actif et joignable.",
                    tailscaleIp,
                    config.Port,
                    health.NodeId,
                    health.NodeName,
                    health.AgentVersion,
                    health.BrainReady,
                    health.BrainModel,
                    health.FfmpegReady,
                    health.ActiveTaskCount,
                    HasManagedRuntime()));
                return;
            }
        }

        if (_lastProbeRejectedBinding && !HasManagedRuntime())
        {
            Publish(new NodeStatusSnapshot(
                NodeRunState.Error,
                "Un ancien runtime écoute sur ce port sans protection Tailscale stricte. Ferme-le ou redémarre Windows.",
                tailscaleIp,
                port,
                config?.NodeId ?? string.Empty,
                config?.NodeName ?? string.Empty));
            return;
        }

        if (ManagedRuntimeExited())
        {
            ScheduleRestart("Le runtime s'est arrêté.");
        }

        if (HasManagedRuntime())
        {
            _healthFailures++;
            if (_healthFailures >= 4)
            {
                StopRuntime();
                ScheduleRestart("Le runtime ne répond plus.");
            }
            else
            {
                Publish(new NodeStatusSnapshot(
                    NodeRunState.Starting,
                    "Le runtime démarre…",
                    tailscaleIp,
                    port,
                    config?.NodeId ?? string.Empty,
                    config?.NodeName ?? string.Empty,
                    ManagedRuntime: true));
            }

            return;
        }

        if (DateTimeOffset.UtcNow < _restartNotBefore)
        {
            var seconds = Math.Max(1, (int)Math.Ceiling((_restartNotBefore - DateTimeOffset.UtcNow).TotalSeconds));
            Publish(new NodeStatusSnapshot(
                NodeRunState.Starting,
                $"Nouvelle tentative dans {seconds} s…",
                tailscaleIp,
                port,
                config?.NodeId ?? string.Empty,
                config?.NodeName ?? string.Empty));
            return;
        }

        if (!hasConfig && !string.IsNullOrEmpty(configError))
        {
            Log(AppPaths.SupervisorLog, configError);
        }

        StartRuntime(tailscaleIp);
        Publish(new NodeStatusSnapshot(
            NodeRunState.Starting,
            "Démarrage sécurisé du runtime sur Tailscale…",
            tailscaleIp,
            port,
            config?.NodeId ?? string.Empty,
            config?.NodeName ?? string.Empty,
            ManagedRuntime: true));
    }

    private async Task<RuntimeHealth?> ProbeHealthAsync(
        string tailscaleIp,
        NodeAgentConfig config,
        CancellationToken cancellationToken)
    {
        _lastProbeRejectedBinding = false;
        try
        {
            using var request = new HttpRequestMessage(
                HttpMethod.Get,
                $"http://{tailscaleIp}:{config.Port}/health");
            request.Headers.TryAddWithoutValidation("X-Jade-Token", config.Token);
            using var response = await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            var json = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (!RuntimeHealth.TryParse(json, out var health, out var error))
            {
                Log(AppPaths.SupervisorLog, error);
                return null;
            }

            if (health is null
                || !string.Equals(health.BindAddress, tailscaleIp, StringComparison.Ordinal))
            {
                _lastProbeRejectedBinding = true;
                Log(
                    AppPaths.SupervisorLog,
                    "Runtime rejected because it is not bound exclusively to the active Tailscale address.");
                return null;
            }

            return health;
        }
        catch (HttpRequestException)
        {
            return null;
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return null;
        }
    }

    private void StartRuntime(string tailscaleIp)
    {
        var executable = PayloadInstaller.EnsureRuntime();
        var startInfo = new ProcessStartInfo
        {
            FileName = executable,
            WorkingDirectory = AppPaths.RuntimeDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        startInfo.ArgumentList.Add("--node-kind");
        startInfo.ArgumentList.Add("PC");
        startInfo.ArgumentList.Add("--bind-address");
        startInfo.ArgumentList.Add(tailscaleIp);
        startInfo.ArgumentList.Add("--channel");
        startInfo.ArgumentList.Add("stable");
        startInfo.Environment["JADE_GENESIS_CONFIG_DIR"] = AppPaths.ConfigDirectory;
        startInfo.Environment["PYTHONUNBUFFERED"] = "1";

        var process = new Process
        {
            StartInfo = startInfo,
            EnableRaisingEvents = true,
        };
        process.OutputDataReceived += (_, eventArgs) =>
        {
            if (!string.IsNullOrEmpty(eventArgs.Data))
            {
                Log(AppPaths.RuntimeLog, eventArgs.Data);
            }
        };
        process.ErrorDataReceived += (_, eventArgs) =>
        {
            if (!string.IsNullOrEmpty(eventArgs.Data))
            {
                Log(AppPaths.RuntimeLog, $"ERROR {eventArgs.Data}");
            }
        };

        if (!process.Start())
        {
            process.Dispose();
            throw new InvalidOperationException("Impossible de démarrer le runtime Jade.");
        }

        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        lock (_processLock)
        {
            _runtimeProcess = process;
            _runtimeBindAddress = tailscaleIp;
        }

        _healthFailures = 0;
        Log(AppPaths.SupervisorLog, $"Runtime started on {tailscaleIp} (pid {process.Id}).");
    }

    private async Task EnsureOllamaAsync(CancellationToken cancellationToken)
    {
        if (DateTimeOffset.UtcNow < _nextOllamaProbe)
        {
            return;
        }

        _nextOllamaProbe = DateTimeOffset.UtcNow.AddSeconds(30);
        try
        {
            using var response = await _httpClient.GetAsync("http://127.0.0.1:11434/api/tags", cancellationToken)
                .ConfigureAwait(false);
            if (response.IsSuccessStatusCode)
            {
                return;
            }
        }
        catch (HttpRequestException)
        {
            // Start the installed local service below.
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // Start the installed local service below.
        }

        lock (_processLock)
        {
            if (_ollamaProcess is { HasExited: false })
            {
                return;
            }

            _ollamaProcess?.Dispose();
            _ollamaProcess = null;
        }

        var executable = FindOllamaExecutable();
        if (executable is null)
        {
            return;
        }

        var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = executable,
                Arguments = "serve",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            },
            EnableRaisingEvents = true,
        };
        process.OutputDataReceived += (_, eventArgs) =>
        {
            if (!string.IsNullOrEmpty(eventArgs.Data))
            {
                Log(AppPaths.OllamaLog, eventArgs.Data);
            }
        };
        process.ErrorDataReceived += (_, eventArgs) =>
        {
            if (!string.IsNullOrEmpty(eventArgs.Data))
            {
                Log(AppPaths.OllamaLog, $"ERROR {eventArgs.Data}");
            }
        };

        try
        {
            if (!process.Start())
            {
                process.Dispose();
                return;
            }

            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            lock (_processLock)
            {
                _ollamaProcess = process;
            }

            Log(AppPaths.SupervisorLog, $"Ollama serve started (pid {process.Id}).");
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            process.Dispose();
            Log(AppPaths.SupervisorLog, $"Ollama start failed: {exception.GetType().Name}.");
        }
    }

    private static string? FindOllamaExecutable()
    {
        var candidates = new List<string>
        {
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Programs",
                "Ollama",
                "ollama.exe"),
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "Ollama",
                "ollama.exe"),
        };
        var path = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        candidates.AddRange(path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Select(directory => Path.Combine(directory.Trim(), "ollama.exe")));
        return candidates.FirstOrDefault(File.Exists);
    }

    private int ReadPort() => NodeAgentConfig.TryLoad(AppPaths.NodeConfigPath, out var config, out _)
        ? config?.Port ?? NodeAgentConfig.DefaultPort
        : NodeAgentConfig.DefaultPort;

    private bool HasManagedRuntime()
    {
        lock (_processLock)
        {
            return _runtimeProcess is { HasExited: false };
        }
    }

    private bool ManagedRuntimeExited()
    {
        lock (_processLock)
        {
            if (_runtimeProcess is null || !_runtimeProcess.HasExited)
            {
                return false;
            }

            Log(AppPaths.SupervisorLog, $"Runtime exited with code {_runtimeProcess.ExitCode}.");
            _runtimeProcess.Dispose();
            _runtimeProcess = null;
            _runtimeBindAddress = null;
            return true;
        }
    }

    private void StopRuntime()
    {
        Process? process;
        lock (_processLock)
        {
            process = _runtimeProcess;
            _runtimeProcess = null;
            _runtimeBindAddress = null;
        }

        StopProcess(process);
    }

    private void StopOllama()
    {
        Process? process;
        lock (_processLock)
        {
            process = _ollamaProcess;
            _ollamaProcess = null;
        }

        StopProcess(process);
    }

    private static void StopProcess(Process? process)
    {
        if (process is null)
        {
            return;
        }

        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(5_000);
            }
        }
        catch (InvalidOperationException)
        {
            // Process already ended.
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // Windows is already shutting down or denied the late cleanup.
        }
        finally
        {
            process.Dispose();
        }
    }

    private void ScheduleRestart(string reason)
    {
        _restartFailures = Math.Min(_restartFailures + 1, 6);
        var delaySeconds = Math.Min(60, 1 << _restartFailures);
        _restartNotBefore = DateTimeOffset.UtcNow.AddSeconds(delaySeconds);
        Log(AppPaths.SupervisorLog, $"{reason} Restart in {delaySeconds}s.");
    }

    private void Publish(NodeStatusSnapshot status)
    {
        Current = status;
        StatusChanged?.Invoke(this, status);
    }

    private void Log(string path, string message)
    {
        try
        {
            lock (_logLock)
            {
                AppPaths.EnsureDirectories();
                File.AppendAllText(
                    path,
                    $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}");
            }
        }
        catch (IOException)
        {
            // Logging must never take the node offline.
        }
        catch (UnauthorizedAccessException)
        {
            // Logging must never take the node offline.
        }
    }
}
