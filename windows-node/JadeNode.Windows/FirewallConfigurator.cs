using System.ComponentModel;
using System.Diagnostics;

namespace JadeNode.Windows;

internal static class FirewallConfigurator
{
    private const string RuleName = "Jade Genesis Node (Tailscale uniquement)";

    public static bool IsConfigured(int port)
    {
        if (!File.Exists(AppPaths.FirewallStatePath))
        {
            return false;
        }

        try
        {
            return string.Equals(
                File.ReadAllText(AppPaths.FirewallStatePath).Trim(),
                StateValue(port),
                StringComparison.Ordinal);
        }
        catch (IOException)
        {
            return false;
        }
    }

    public static async Task<bool> RequestElevationAsync(int port)
    {
        if (port is < 1 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(port));
        }

        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = AppPaths.InstalledExecutable,
                Arguments = $"--configure-firewall {port}",
                UseShellExecute = true,
                Verb = "runas",
                WorkingDirectory = AppPaths.InstallDirectory,
            });
            if (process is null)
            {
                return false;
            }

            await process.WaitForExitAsync().ConfigureAwait(false);
            return process.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            return false;
        }
    }

    public static int ConfigureElevated(string[] args)
    {
        if (args.Length != 2 || !int.TryParse(args[1], out var port) || port is < 1 or > 65535)
        {
            return 2;
        }

        AppPaths.EnsureDirectories();
        try
        {
            _ = RunNetsh($"advfirewall firewall delete rule name=\"{RuleName}\"");
            var escapedProgram = AppPaths.RuntimeExecutable.Replace("\"", string.Empty, StringComparison.Ordinal);
            var result = RunNetsh(
                $"advfirewall firewall add rule name=\"{RuleName}\" " +
                "dir=in action=allow enable=yes profile=any protocol=TCP " +
                $"localport={port} remoteip=100.64.0.0/10 program=\"{escapedProgram}\"");
            if (result != 0)
            {
                return result;
            }

            File.WriteAllText(AppPaths.FirewallStatePath, StateValue(port));
            return 0;
        }
        catch (IOException)
        {
            return 1;
        }
        catch (UnauthorizedAccessException)
        {
            return 1;
        }
    }

    private static int RunNetsh(string arguments)
    {
        using var process = Process.Start(new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "netsh.exe"),
            Arguments = arguments,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        }) ?? throw new InvalidOperationException("netsh.exe indisponible.");
        process.WaitForExit();
        return process.ExitCode;
    }

    private static string StateValue(int port) => $"v1|{port}|{AppPaths.RuntimeExecutable}";
}
