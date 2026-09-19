using System.Text.Json;

namespace JadeNode.Core;

public sealed record RuntimeHealth(
    string Protocol,
    string AgentVersion,
    string BindAddress,
    string NodeId,
    string NodeName,
    bool BrainReady,
    string BrainModel,
    bool FfmpegReady,
    int ActiveTaskCount)
{
    public const string ExpectedProtocol = "jade-genesis-node/0.0.6";

    public static bool TryParse(string json, out RuntimeHealth? health, out string error)
    {
        health = null;
        error = string.Empty;
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            var protocol = ReadString(root, "protocol");
            if (!string.Equals(protocol, ExpectedProtocol, StringComparison.Ordinal))
            {
                error = $"Protocole runtime incompatible : {protocol}";
                return false;
            }

            var ffmpegReady = root.TryGetProperty("ffmpeg_transcode_probe", out var probe)
                && probe.ValueKind == JsonValueKind.Object
                && probe.TryGetProperty("ready", out var ready)
                && ready.ValueKind is JsonValueKind.True;

            health = new RuntimeHealth(
                protocol,
                ReadString(root, "agent_version"),
                ReadString(root, "bind_address"),
                ReadString(root, "node_id"),
                ReadString(root, "name"),
                ReadBoolean(root, "brain_ready"),
                ReadString(root, "brain_model"),
                ffmpegReady,
                ReadInteger(root, "active_task_count"));
            return true;
        }
        catch (JsonException)
        {
            error = "Réponse de santé du runtime invalide.";
            return false;
        }
    }

    private static string ReadString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;

    private static bool ReadBoolean(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;

    private static int ReadInteger(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.TryGetInt32(out var parsed)
            ? Math.Max(0, parsed)
            : 0;
}
