using System.Security.Cryptography;
using System.Text.Json;

namespace JadeNode.Core;

public sealed record GitHubRelease(
    Version Version,
    string Tag,
    Uri DownloadUri,
    long Size,
    string Sha256)
{
    public const string AssetName = "JadeNode.exe";
    public const long MaximumAssetBytes = 350L * 1024L * 1024L;
    private const string TagPrefix = "jade-node-v";

    public static bool TryParseAvailable(string json, out GitHubRelease? release, out string error)
    {
        release = null;
        error = "Aucune version Jade Node stable avec empreinte SHA-256.";
        try
        {
            using var document = JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != JsonValueKind.Array)
            {
                return TryParseLatest(json, out release, out error);
            }

            foreach (var item in document.RootElement.EnumerateArray())
            {
                if (!TryParseLatest(item.GetRawText(), out var candidate, out _) || candidate is null)
                {
                    continue;
                }

                if (release is null || candidate.Version > release.Version)
                {
                    release = candidate;
                }
            }

            return release is not null;
        }
        catch (JsonException)
        {
            error = "Réponse GitHub invalide.";
            return false;
        }
    }

    public static bool TryParseLatest(string json, out GitHubRelease? release, out string error)
    {
        release = null;
        error = string.Empty;
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (ReadBoolean(root, "draft") || ReadBoolean(root, "prerelease"))
            {
                error = "La dernière version publiée n'est pas stable.";
                return false;
            }

            var tag = ReadString(root, "tag_name");
            if (!tag.StartsWith(TagPrefix, StringComparison.Ordinal)
                || !Version.TryParse(tag[TagPrefix.Length..], out var version))
            {
                error = "Tag de mise à jour Jade Node invalide.";
                return false;
            }

            if (!root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
            {
                error = "Aucun exécutable dans cette version.";
                return false;
            }

            foreach (var asset in assets.EnumerateArray())
            {
                if (!string.Equals(ReadString(asset, "name"), AssetName, StringComparison.Ordinal))
                {
                    continue;
                }

                var size = asset.TryGetProperty("size", out var sizeValue) && sizeValue.TryGetInt64(out var parsedSize)
                    ? parsedSize
                    : 0;
                var digest = ReadString(asset, "digest");
                var browserUrl = ReadString(asset, "browser_download_url");
                if (size is <= 0 or > MaximumAssetBytes)
                {
                    error = "Taille de mise à jour refusée.";
                    return false;
                }

                if (!digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                {
                    error = "Empreinte SHA-256 GitHub absente.";
                    return false;
                }

                var sha256 = digest["sha256:".Length..].ToLowerInvariant();
                if (sha256.Length != 64 || sha256.Any(value => !Uri.IsHexDigit(value)))
                {
                    error = "Empreinte SHA-256 GitHub invalide.";
                    return false;
                }

                if (!Uri.TryCreate(browserUrl, UriKind.Absolute, out var downloadUri)
                    || downloadUri.Scheme != Uri.UriSchemeHttps
                    || !string.Equals(downloadUri.Host, "github.com", StringComparison.OrdinalIgnoreCase)
                    || !downloadUri.AbsolutePath.StartsWith("/vadorus/Jade-genesis/releases/download/", StringComparison.Ordinal))
                {
                    error = "Adresse de mise à jour hors du dépôt Jade Genesis.";
                    return false;
                }

                release = new GitHubRelease(version, tag, downloadUri, size, sha256);
                return true;
            }

            error = "JadeNode.exe absent de cette version.";
            return false;
        }
        catch (JsonException)
        {
            error = "Réponse GitHub invalide.";
            return false;
        }
    }

    public static async Task<bool> VerifySha256Async(
        Stream stream,
        string expectedSha256,
        CancellationToken cancellationToken = default)
    {
        var digest = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        var actual = Convert.ToHexString(digest).ToLowerInvariant();
        return CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.ASCII.GetBytes(actual),
            System.Text.Encoding.ASCII.GetBytes(expectedSha256.ToLowerInvariant()));
    }

    private static string ReadString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    private static bool ReadBoolean(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;
}
