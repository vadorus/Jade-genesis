using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace JadeNode.Windows;

internal static class PayloadInstaller
{
    private const string RuntimeResource = "JadeNode.Payload.JadeNodeRuntime.exe";
    private const string ManifestResource = "JadeNode.Payload.runtime-manifest.json";

    public static string EnsureRuntime()
    {
        AppPaths.EnsureDirectories();
        var assembly = Assembly.GetExecutingAssembly();
        using var manifestStream = assembly.GetManifestResourceStream(ManifestResource)
            ?? throw new InvalidOperationException("Manifest du runtime embarqué absent.");
        using var document = JsonDocument.Parse(manifestStream);
        var expectedSha = document.RootElement.GetProperty("sha256").GetString()?.ToLowerInvariant() ?? string.Empty;
        if (expectedSha.Length != 64 || expectedSha.Any(value => !Uri.IsHexDigit(value)))
        {
            throw new InvalidOperationException("Empreinte du runtime embarqué invalide.");
        }

        if (File.Exists(AppPaths.RuntimeExecutable)
            && string.Equals(HashFile(AppPaths.RuntimeExecutable), expectedSha, StringComparison.Ordinal))
        {
            return AppPaths.RuntimeExecutable;
        }

        var temporary = AppPaths.RuntimeExecutable + ".new";
        using (var resource = assembly.GetManifestResourceStream(RuntimeResource)
            ?? throw new InvalidOperationException("Runtime Windows embarqué absent."))
        using (var output = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            resource.CopyTo(output);
            output.Flush(flushToDisk: true);
        }

        if (!string.Equals(HashFile(temporary), expectedSha, StringComparison.Ordinal))
        {
            File.Delete(temporary);
            throw new InvalidOperationException("Le runtime embarqué ne correspond pas à son empreinte.");
        }

        File.Move(temporary, AppPaths.RuntimeExecutable, overwrite: true);
        return AppPaths.RuntimeExecutable;
    }

    private static string HashFile(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }
}
