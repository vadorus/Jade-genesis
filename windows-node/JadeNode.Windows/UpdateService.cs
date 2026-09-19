using System.Diagnostics;
using System.Net.Http.Headers;
using System.Reflection;
using JadeNode.Core;

namespace JadeNode.Windows;

internal sealed class UpdateService : IDisposable
{
    private static readonly Uri ReleasesApi = new(
        "https://api.github.com/repos/vadorus/Jade-genesis/releases?per_page=20");
    private readonly HttpClient _httpClient = new()
    {
        Timeout = TimeSpan.FromMinutes(5),
    };

    public UpdateService()
    {
        _httpClient.DefaultRequestHeaders.UserAgent.Add(
            new ProductInfoHeaderValue("JadeNode", CurrentVersion.ToString()));
        _httpClient.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        _httpClient.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
    }

    public static Version CurrentVersion => Assembly.GetExecutingAssembly().GetName().Version
        ?? new Version(0, 0, 0);

    public async Task<string?> TryDownloadNewerAsync(CancellationToken cancellationToken)
    {
        AppPaths.EnsureDirectories();
        using var releasesResponse = await _httpClient.GetAsync(ReleasesApi, cancellationToken)
            .ConfigureAwait(false);
        if (!releasesResponse.IsSuccessStatusCode)
        {
            return null;
        }

        var releasesJson = await releasesResponse.Content.ReadAsStringAsync(cancellationToken)
            .ConfigureAwait(false);
        if (!GitHubRelease.TryParseAvailable(releasesJson, out var release, out _)
            || release is null
            || release.Version <= CurrentVersion)
        {
            return null;
        }

        var finalPath = Path.Combine(AppPaths.UpdatesDirectory, $"JadeNode-{release.Version}.exe");
        var temporaryPath = finalPath + ".download";
        File.Delete(temporaryPath);

        using var downloadResponse = await _httpClient.GetAsync(
            release.DownloadUri,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        downloadResponse.EnsureSuccessStatusCode();
        if (downloadResponse.Content.Headers.ContentLength is long contentLength
            && contentLength != release.Size)
        {
            throw new InvalidOperationException("Taille de mise à jour inattendue.");
        }

        await using (var source = await downloadResponse.Content.ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false))
        await using (var destination = new FileStream(
            temporaryPath,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.None,
            bufferSize: 128 * 1024,
            useAsync: true))
        {
            await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        var downloadedSize = new FileInfo(temporaryPath).Length;
        if (downloadedSize != release.Size)
        {
            File.Delete(temporaryPath);
            throw new InvalidOperationException("Téléchargement de mise à jour incomplet.");
        }

        await using (var stream = new FileStream(
            temporaryPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            bufferSize: 128 * 1024,
            useAsync: true))
        {
            if (!await GitHubRelease.VerifySha256Async(stream, release.Sha256, cancellationToken)
                .ConfigureAwait(false))
            {
                File.Delete(temporaryPath);
                throw new InvalidOperationException("Empreinte SHA-256 de mise à jour refusée.");
            }
        }

        File.Move(temporaryPath, finalPath, overwrite: true);
        return finalPath;
    }

    public static void LaunchStagedUpdate(string stagedExecutable)
    {
        var fullPath = Path.GetFullPath(stagedExecutable);
        var updateRoot = Path.GetFullPath(AppPaths.UpdatesDirectory) + Path.DirectorySeparatorChar;
        if (!fullPath.StartsWith(updateRoot, StringComparison.OrdinalIgnoreCase) || !File.Exists(fullPath))
        {
            throw new InvalidOperationException("Chemin de mise à jour refusé.");
        }

        Process.Start(new ProcessStartInfo
        {
            FileName = fullPath,
            Arguments = $"--apply-update {Environment.ProcessId}",
            UseShellExecute = true,
            WorkingDirectory = AppPaths.UpdatesDirectory,
        }) ?? throw new InvalidOperationException("Impossible de lancer la mise à jour.");
    }

    public static int ApplyStagedUpdate(string[] args)
    {
        if (args.Length != 2 || !int.TryParse(args[1], out var oldProcessId) || oldProcessId <= 0)
        {
            return 2;
        }

        var current = Path.GetFullPath(AppPaths.CurrentExecutable);
        var updateRoot = Path.GetFullPath(AppPaths.UpdatesDirectory) + Path.DirectorySeparatorChar;
        if (!current.StartsWith(updateRoot, StringComparison.OrdinalIgnoreCase))
        {
            return 2;
        }

        try
        {
            using var oldProcess = Process.GetProcessById(oldProcessId);
            oldProcess.WaitForExit(30_000);
            if (!oldProcess.HasExited)
            {
                return 1;
            }
        }
        catch (ArgumentException)
        {
            // The old process already exited.
        }

        try
        {
            AppPaths.EnsureDirectories();
            var temporary = AppPaths.InstalledExecutable + ".update";
            File.Copy(current, temporary, overwrite: true);
            File.Move(temporary, AppPaths.InstalledExecutable, overwrite: true);
            StartupRegistration.SetEnabled(enabled: true);
            Process.Start(new ProcessStartInfo
            {
                FileName = AppPaths.InstalledExecutable,
                Arguments = "--background --updated",
                UseShellExecute = true,
                WorkingDirectory = AppPaths.InstallDirectory,
            });
            return 0;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return 1;
        }
    }

    public void Dispose() => _httpClient.Dispose();
}
