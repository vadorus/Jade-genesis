using System.Diagnostics;

namespace JadeNode.Windows;

internal static class AppPaths
{
    public static string InstallDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Jade Genesis",
        "Node");

    public static string InstalledExecutable { get; } = Path.Combine(InstallDirectory, "JadeNode.exe");

    public static string RuntimeDirectory { get; } = Path.Combine(InstallDirectory, "Runtime");

    public static string RuntimeExecutable { get; } = Path.Combine(RuntimeDirectory, "JadeNodeRuntime.exe");

    public static string LogsDirectory { get; } = Path.Combine(InstallDirectory, "Logs");

    public static string RuntimeLog { get; } = Path.Combine(LogsDirectory, "runtime.log");

    public static string OllamaLog { get; } = Path.Combine(LogsDirectory, "ollama.log");

    public static string SupervisorLog { get; } = Path.Combine(LogsDirectory, "supervisor.log");

    public static string UpdatesDirectory { get; } = Path.Combine(InstallDirectory, "Updates");

    public static string FirewallStatePath { get; } = Path.Combine(InstallDirectory, "firewall-state.txt");

    public static string ConfigDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ".jade-genesis");

    public static string NodeConfigPath { get; } = Path.Combine(ConfigDirectory, "node-agent.json");

    public static string CurrentExecutable => Environment.ProcessPath
        ?? Process.GetCurrentProcess().MainModule?.FileName
        ?? throw new InvalidOperationException("Chemin de JadeNode.exe introuvable.");

    public static bool IsInstalledProcess => PathsEqual(CurrentExecutable, InstalledExecutable);

    public static void EnsureDirectories()
    {
        Directory.CreateDirectory(InstallDirectory);
        Directory.CreateDirectory(RuntimeDirectory);
        Directory.CreateDirectory(LogsDirectory);
        Directory.CreateDirectory(UpdatesDirectory);
    }

    public static bool PathsEqual(string left, string right) => string.Equals(
        Path.GetFullPath(left).TrimEnd(Path.DirectorySeparatorChar),
        Path.GetFullPath(right).TrimEnd(Path.DirectorySeparatorChar),
        StringComparison.OrdinalIgnoreCase);
}
