using System.Diagnostics;

namespace JadeNode.Windows;

internal static class BootstrapInstaller
{
    public static bool InstallAndLaunchIfNeeded()
    {
        if (AppPaths.IsInstalledProcess)
        {
            return false;
        }

        AppPaths.EnsureDirectories();
        var temporary = AppPaths.InstalledExecutable + ".new";
        File.Copy(AppPaths.CurrentExecutable, temporary, overwrite: true);
        File.Move(temporary, AppPaths.InstalledExecutable, overwrite: true);
        StartupRegistration.SetEnabled(enabled: true);

        Process.Start(new ProcessStartInfo
        {
            FileName = AppPaths.InstalledExecutable,
            Arguments = $"--first-run --wait-for-pid {Environment.ProcessId}",
            UseShellExecute = true,
            WorkingDirectory = AppPaths.InstallDirectory,
        });
        return true;
    }
}
