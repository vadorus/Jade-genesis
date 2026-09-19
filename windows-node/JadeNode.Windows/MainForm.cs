using System.Diagnostics;
using System.Drawing;
using JadeNode.Core;

namespace JadeNode.Windows;

internal sealed class MainForm : Form
{
    private readonly NodeSupervisor _supervisor;
    private readonly UpdateService _updateService = new();
    private readonly CancellationTokenSource _lifetime = new();
    private readonly bool _firstRun;
    private readonly NotifyIcon _notifyIcon;
    private readonly Label _statusValue = ValueLabel();
    private readonly Label _tailscaleValue = ValueLabel();
    private readonly Label _addressValue = ValueLabel();
    private readonly Label _identityValue = ValueLabel();
    private readonly Label _runtimeValue = ValueLabel();
    private readonly Label _ollamaValue = ValueLabel();
    private readonly Label _ffmpegValue = ValueLabel();
    private readonly Label _updateValue = ValueLabel();
    private readonly CheckBox _startupCheck = new()
    {
        AutoSize = true,
        Text = "Démarrer Jade Node avec Windows",
    };
    private readonly Button _copyButton = new()
    {
        AutoSize = true,
        Text = "Copier les infos pour le Pixel",
        Enabled = false,
    };
    private readonly Button _restartButton = new()
    {
        AutoSize = true,
        Text = "Redémarrer le runtime",
    };
    private readonly Button _firewallButton = new()
    {
        AutoSize = true,
        Text = "Autoriser Tailscale dans le pare-feu",
    };
    private readonly Button _updateButton = new()
    {
        AutoSize = true,
        Text = "Installer la mise à jour",
        Visible = false,
    };
    private bool _allowExit;
    private bool _changingStartup;
    private bool _shutdownStarted;
    private string? _stagedUpdate;
    private NodeRunState? _lastNotifiedState;

    public MainForm(bool startHidden, bool firstRun, bool updated)
    {
        _firstRun = firstRun;
        _supervisor = new NodeSupervisor();
        _supervisor.StatusChanged += SupervisorOnStatusChanged;

        Text = "Jade Node pour Windows";
        Icon = SystemIcons.Application;
        ClientSize = new Size(710, 510);
        MinimumSize = new Size(650, 470);
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10F, FontStyle.Regular, GraphicsUnit.Point);

        var title = new Label
        {
            AutoSize = true,
            Font = new Font(Font.FontFamily, 18F, FontStyle.Bold),
            Text = "Jade Node",
            Margin = new Padding(0, 0, 0, 4),
        };
        var subtitle = new Label
        {
            AutoSize = true,
            ForeColor = Color.DimGray,
            Text = "Relie ce PC à Jade par Tailscale, sans terminal.",
            Margin = new Padding(0, 0, 0, 18),
        };

        var statusTable = new TableLayoutPanel
        {
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = 2,
            Dock = DockStyle.Top,
            Padding = new Padding(0),
        };
        statusTable.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170F));
        statusTable.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
        AddRow(statusTable, "État", _statusValue);
        AddRow(statusTable, "Tailscale", _tailscaleValue);
        AddRow(statusTable, "Adresse du PC", _addressValue);
        AddRow(statusTable, "Identité du nœud", _identityValue);
        AddRow(statusTable, "Runtime", _runtimeValue);
        AddRow(statusTable, "Ollama", _ollamaValue);
        AddRow(statusTable, "FFmpeg", _ffmpegValue);
        AddRow(statusTable, "Mises à jour", _updateValue);

        var buttons = new FlowLayoutPanel
        {
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Dock = DockStyle.Top,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Margin = new Padding(0, 18, 0, 8),
        };
        buttons.Controls.Add(_copyButton);
        buttons.Controls.Add(_restartButton);
        buttons.Controls.Add(_firewallButton);
        buttons.Controls.Add(_updateButton);

        var note = new Label
        {
            AutoSize = true,
            MaximumSize = new Size(640, 0),
            ForeColor = Color.DimGray,
            Text = "Le service écoute uniquement sur l’adresse Tailscale. Le jeton reste dans ton profil Windows et n’est copié que lorsque tu appuies sur le bouton Pixel.",
            Margin = new Padding(0, 12, 0, 0),
        };

        var content = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            Dock = DockStyle.Fill,
            AutoScroll = true,
            Padding = new Padding(28, 24, 28, 20),
        };
        content.Controls.Add(title);
        content.Controls.Add(subtitle);
        content.Controls.Add(statusTable);
        content.Controls.Add(buttons);
        content.Controls.Add(_startupCheck);
        content.Controls.Add(note);
        Controls.Add(content);

        _notifyIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Jade Node — initialisation",
            Visible = true,
            ContextMenuStrip = BuildTrayMenu(),
        };
        _notifyIcon.DoubleClick += (_, _) => ShowWindow();

        _copyButton.Click += (_, _) => CopyPairingInformation();
        _restartButton.Click += async (_, _) => await _supervisor.RestartAsync();
        _firewallButton.Click += async (_, _) => await ConfigureFirewallAsync(showSuccess: true);
        _updateButton.Click += async (_, _) => await ApplyStagedUpdateAsync();
        _startupCheck.Checked = StartupRegistration.IsEnabled();
        _startupCheck.CheckedChanged += (_, _) => ToggleStartup();

        _statusValue.Text = "Initialisation…";
        _tailscaleValue.Text = "Recherche…";
        _addressValue.Text = "—";
        _identityValue.Text = "—";
        _runtimeValue.Text = "—";
        _ollamaValue.Text = "Vérification…";
        _ffmpegValue.Text = "Vérification…";
        _updateValue.Text = updated ? "Mise à jour installée." : "Canal stable, vérification automatique.";

        Shown += async (_, _) =>
        {
            try
            {
                await _supervisor.StartAsync();
                if (startHidden)
                {
                    Hide();
                }
                else
                {
                    Activate();
                }

                if (_firstRun)
                {
                    await Task.Delay(2_000, _lifetime.Token);
                    await ConfigureFirewallAsync(showSuccess: false);
                }

                _ = CheckForUpdatesAfterDelayAsync();
            }
            catch (OperationCanceledException)
            {
                // Window closed during startup.
            }
            catch (Exception exception)
            {
                ShowFailure("Jade Node n'a pas pu démarrer", exception.Message);
            }
        };
    }

    protected override void OnFormClosing(FormClosingEventArgs eventArgs)
    {
        if (!_allowExit && eventArgs.CloseReason == CloseReason.UserClosing)
        {
            eventArgs.Cancel = true;
            Hide();
            _notifyIcon.ShowBalloonTip(
                2_000,
                "Jade Node reste actif",
                "Le PC reste disponible pour Jade. Utilise l’icône près de l’horloge pour rouvrir la fenêtre.",
                ToolTipIcon.Info);
            return;
        }

        ShutdownOnce();
        base.OnFormClosing(eventArgs);
    }

    private ContextMenuStrip BuildTrayMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Ouvrir Jade Node", null, (_, _) => ShowWindow());
        menu.Items.Add("Redémarrer le runtime", null, async (_, _) => await _supervisor.RestartAsync());
        menu.Items.Add("Ouvrir les journaux", null, (_, _) => OpenLogs());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quitter", null, async (_, _) => await ExitAsync());
        return menu;
    }

    private void SupervisorOnStatusChanged(object? sender, NodeStatusSnapshot status)
    {
        if (IsDisposed || !IsHandleCreated)
        {
            return;
        }

        try
        {
            BeginInvoke(new Action(() => RenderStatus(status)));
        }
        catch (InvalidOperationException)
        {
            // Window is closing.
        }
    }

    private void RenderStatus(NodeStatusSnapshot status)
    {
        _statusValue.Text = status.Message;
        _statusValue.ForeColor = status.State switch
        {
            NodeRunState.Online => Color.ForestGreen,
            NodeRunState.Error => Color.Firebrick,
            NodeRunState.WaitingForTailscale => Color.DarkOrange,
            _ => SystemColors.ControlText,
        };
        _tailscaleValue.Text = string.IsNullOrWhiteSpace(status.TailscaleIp)
            ? "Hors ligne"
            : $"Connecté — {status.TailscaleIp}";
        _addressValue.Text = string.IsNullOrWhiteSpace(status.TailscaleIp) || status.Port == 0
            ? "—"
            : $"{status.TailscaleIp}:{status.Port}";
        _identityValue.Text = string.IsNullOrWhiteSpace(status.NodeId)
            ? "Création en cours…"
            : $"{status.NodeName} — {status.NodeId}";
        _runtimeValue.Text = string.IsNullOrWhiteSpace(status.RuntimeVersion)
            ? status.State == NodeRunState.Starting ? "Démarrage…" : "—"
            : $"Version {status.RuntimeVersion} — {(status.ManagedRuntime ? "surveillé" : "déjà actif")}";
        _ollamaValue.Text = status.BrainReady
            ? $"Prêt — {status.BrainModel}"
            : "Non prêt — Ollama ou modèle local à vérifier";
        _ffmpegValue.Text = status.FfmpegReady ? "Prêt" : "Non détecté";
        _copyButton.Enabled = status.State == NodeRunState.Online
            && !string.IsNullOrWhiteSpace(status.TailscaleIp);
        _firewallButton.Text = FirewallConfigurator.IsConfigured(status.Port == 0 ? NodeAgentConfig.DefaultPort : status.Port)
            ? "Pare-feu Tailscale configuré"
            : "Autoriser Tailscale dans le pare-feu";

        var trayText = status.State == NodeRunState.Online ? "Jade Node — PC prêt" : "Jade Node — attention requise";
        _notifyIcon.Text = trayText.Length <= 63 ? trayText : trayText[..63];
        if (_lastNotifiedState != status.State && status.State is NodeRunState.Online or NodeRunState.WaitingForTailscale)
        {
            _notifyIcon.ShowBalloonTip(
                2_000,
                status.State == NodeRunState.Online ? "PC prêt pour Jade" : "Tailscale requis",
                status.Message,
                status.State == NodeRunState.Online ? ToolTipIcon.Info : ToolTipIcon.Warning);
        }

        _lastNotifiedState = status.State;
    }

    private void CopyPairingInformation()
    {
        var status = _supervisor.Current;
        if (!NodeAgentConfig.TryLoad(AppPaths.NodeConfigPath, out var config, out var error)
            || config is null
            || string.IsNullOrWhiteSpace(status.TailscaleIp))
        {
            ShowFailure("Informations indisponibles", error);
            return;
        }

        Clipboard.SetText(
            $"Adresse Tailscale : {status.TailscaleIp}{Environment.NewLine}" +
            $"Port : {config.Port}{Environment.NewLine}" +
            $"Jeton : {config.Token}");
        MessageBox.Show(
            this,
            "Adresse, port et jeton copiés. Colle-les dans l’écran Nœuds de Jade sur le Pixel. Ne partage pas ce jeton.",
            "Informations copiées",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private async Task ConfigureFirewallAsync(bool showSuccess)
    {
        var port = _supervisor.Current.Port is > 0 and <= 65535
            ? _supervisor.Current.Port
            : NodeAgentConfig.DefaultPort;
        if (FirewallConfigurator.IsConfigured(port))
        {
            _firewallButton.Text = "Pare-feu Tailscale configuré";
            return;
        }

        var configured = await FirewallConfigurator.RequestElevationAsync(port);
        _firewallButton.Text = configured
            ? "Pare-feu Tailscale configuré"
            : "Autoriser Tailscale dans le pare-feu";
        if (showSuccess || !configured)
        {
            MessageBox.Show(
                this,
                configured
                    ? "Le pare-feu autorise maintenant Jade uniquement depuis les adresses Tailscale."
                    : "La règle n’a pas été créée. Jade Node reste protégé mais le Pixel peut ne pas joindre ce PC tant que l’autorisation Windows n’est pas accordée.",
                configured ? "Pare-feu configuré" : "Autorisation non accordée",
                MessageBoxButtons.OK,
                configured ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        }
    }

    private void ToggleStartup()
    {
        if (_changingStartup)
        {
            return;
        }

        try
        {
            StartupRegistration.SetEnabled(_startupCheck.Checked);
        }
        catch (Exception exception) when (exception is UnauthorizedAccessException or IOException)
        {
            _changingStartup = true;
            try
            {
                _startupCheck.Checked = StartupRegistration.IsEnabled();
            }
            finally
            {
                _changingStartup = false;
            }
            ShowFailure("Démarrage Windows", exception.Message);
        }
    }

    private async Task CheckForUpdatesAfterDelayAsync()
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(20), _lifetime.Token);
            while (!_lifetime.IsCancellationRequested)
            {
                _updateValue.Text = "Vérification du canal stable…";
                _stagedUpdate = await _updateService.TryDownloadNewerAsync(_lifetime.Token);
                if (_stagedUpdate is not null)
                {
                    _updateValue.Text = "Nouvelle version téléchargée et vérifiée (SHA-256).";
                    _updateButton.Visible = true;
                    if (_supervisor.Current.ActiveTaskCount == 0)
                    {
                        await ApplyStagedUpdateAsync();
                        return;
                    }
                }
                else
                {
                    _updateValue.Text = $"À jour — version {UpdateService.CurrentVersion}.";
                }

                await Task.Delay(TimeSpan.FromHours(6), _lifetime.Token);
            }
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }
        catch (Exception)
        {
            if (!_lifetime.IsCancellationRequested && !IsDisposed)
            {
                _updateValue.Text = "Vérification impossible — nouvel essai automatique plus tard.";
            }
        }
    }

    private async Task ApplyStagedUpdateAsync()
    {
        if (_stagedUpdate is null || !File.Exists(_stagedUpdate))
        {
            return;
        }

        _updateValue.Text = "Installation de la mise à jour…";
        await _supervisor.StopAsync();
        UpdateService.LaunchStagedUpdate(_stagedUpdate);
        _allowExit = true;
        Application.Exit();
    }

    private void OpenLogs()
    {
        AppPaths.EnsureDirectories();
        Process.Start(new ProcessStartInfo
        {
            FileName = "explorer.exe",
            Arguments = $"\"{AppPaths.LogsDirectory}\"",
            UseShellExecute = true,
        });
    }

    private void ShowWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
    }

    private async Task ExitAsync()
    {
        if (_shutdownStarted)
        {
            return;
        }

        _allowExit = true;
        _lifetime.Cancel();
        await _supervisor.StopAsync();
        Application.Exit();
    }

    private void ShutdownOnce()
    {
        if (_shutdownStarted)
        {
            return;
        }

        _shutdownStarted = true;
        _lifetime.Cancel();
        try
        {
            _supervisor.StopAsync().GetAwaiter().GetResult();
        }
        catch (OperationCanceledException)
        {
            // Normal shutdown.
        }

        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _updateService.Dispose();
        _lifetime.Dispose();
    }

    private void ShowFailure(string title, string message)
    {
        MessageBox.Show(
            this,
            string.IsNullOrWhiteSpace(message) ? "Une erreur inattendue est survenue." : message,
            title,
            MessageBoxButtons.OK,
            MessageBoxIcon.Error);
    }

    private static Label ValueLabel() => new()
    {
        AutoSize = true,
        MaximumSize = new Size(460, 0),
        Margin = new Padding(4, 4, 4, 8),
    };

    private static void AddRow(TableLayoutPanel table, string label, Control value)
    {
        var row = table.RowCount++;
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        table.Controls.Add(new Label
        {
            AutoSize = true,
            Font = new Font(SystemFonts.MessageBoxFont, FontStyle.Bold),
            Text = label,
            Margin = new Padding(0, 4, 8, 8),
        }, 0, row);
        table.Controls.Add(value, 1, row);
    }
}
