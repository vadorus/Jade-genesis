using System.Diagnostics;

namespace JadeNode.Windows;

internal static class Program
{
    private const string MutexName = "Local\\JadeGenesis.Node.Windows.v1";

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            if (args.Length > 0 && string.Equals(args[0], "--configure-firewall", StringComparison.Ordinal))
            {
                return FirewallConfigurator.ConfigureElevated(args);
            }

            if (args.Length > 0 && string.Equals(args[0], "--apply-update", StringComparison.Ordinal))
            {
                return UpdateService.ApplyStagedUpdate(args);
            }

            if (args.Length == 1 && string.Equals(args[0], "--self-test", StringComparison.Ordinal))
            {
                var runtime = PayloadInstaller.EnsureRuntime();
                return File.Exists(runtime) && new FileInfo(runtime).Length > 0 ? 0 : 1;
            }

            if (BootstrapInstaller.InstallAndLaunchIfNeeded())
            {
                return 0;
            }

            WaitForBootstrapParent(args);
            using var mutex = new Mutex(initiallyOwned: true, MutexName, out var isFirstInstance);
            if (!isFirstInstance)
            {
                MessageBox.Show(
                    "Jade Node est déjà actif près de l’horloge Windows.",
                    "Jade Node",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return 0;
            }

            Application.SetHighDpiMode(HighDpiMode.SystemAware);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            var startHidden = args.Contains("--background", StringComparer.Ordinal);
            var firstRun = args.Contains("--first-run", StringComparer.Ordinal);
            var updated = args.Contains("--updated", StringComparer.Ordinal);
            Application.Run(new MainForm(startHidden, firstRun, updated));
            return 0;
        }
        catch (Exception exception)
        {
            TryLogFatal(exception);
            MessageBox.Show(
                $"Jade Node n’a pas pu démarrer.\n\n{exception.Message}",
                "Jade Node",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    private static void WaitForBootstrapParent(string[] args)
    {
        var index = Array.FindIndex(args, value => string.Equals(value, "--wait-for-pid", StringComparison.Ordinal));
        if (index < 0 || index + 1 >= args.Length || !int.TryParse(args[index + 1], out var processId))
        {
            return;
        }

        try
        {
            using var parent = Process.GetProcessById(processId);
            parent.WaitForExit(10_000);
        }
        catch (ArgumentException)
        {
            // Bootstrap process already exited.
        }
    }

    private static void TryLogFatal(Exception exception)
    {
        try
        {
            AppPaths.EnsureDirectories();
            File.AppendAllText(
                AppPaths.SupervisorLog,
                $"{DateTimeOffset.Now:O} Fatal {exception.GetType().Name}: {exception.Message}{Environment.NewLine}");
        }
        catch (Exception logException) when (logException is IOException or UnauthorizedAccessException)
        {
            // Nothing else can be done before the UI exists.
        }
    }
}
