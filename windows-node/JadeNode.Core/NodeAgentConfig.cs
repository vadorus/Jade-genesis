using System.Text.Json;

namespace JadeNode.Core;

public sealed class NodeAgentConfig
{
    public const int DefaultPort = 8765;

    public NodeAgentConfig(string nodeId, string nodeName, string token, int port)
    {
        NodeId = nodeId;
        NodeName = nodeName;
        Token = token;
        Port = port;
    }

    public string NodeId { get; }

    public string NodeName { get; }

    public string Token { get; }

    public int Port { get; }

    public static bool TryLoad(string path, out NodeAgentConfig? config, out string error)
    {
        config = null;
        error = string.Empty;
        if (!File.Exists(path))
        {
            error = "Configuration du runtime pas encore créée.";
            return false;
        }

        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            var nodeId = ReadString(root, "node_id");
            var nodeName = ReadString(root, "node_name");
            var token = ReadString(root, "token");
            var port = root.TryGetProperty("port", out var portValue) && portValue.TryGetInt32(out var parsedPort)
                ? parsedPort
                : DefaultPort;

            if (string.IsNullOrWhiteSpace(nodeId) || string.IsNullOrWhiteSpace(token))
            {
                error = "Configuration du runtime incomplète.";
                return false;
            }

            if (port is < 1 or > 65535)
            {
                error = "Port du runtime invalide.";
                return false;
            }

            config = new NodeAgentConfig(
                nodeId.Trim(),
                string.IsNullOrWhiteSpace(nodeName) ? "PC — Jade Genesis" : nodeName.Trim(),
                token.Trim(),
                port);
            return true;
        }
        catch (IOException)
        {
            error = "Configuration du runtime temporairement inaccessible.";
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            error = "Accès refusé à la configuration du runtime.";
            return false;
        }
        catch (JsonException)
        {
            error = "Configuration du runtime invalide.";
            return false;
        }
    }

    public override string ToString() => $"{NodeName} ({NodeId}) sur le port {Port}; jeton masqué";

    private static string ReadString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;
}
