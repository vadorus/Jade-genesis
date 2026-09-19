namespace JadeNode.Windows;

internal enum NodeRunState
{
    Starting,
    Online,
    WaitingForTailscale,
    Error,
    Stopped,
}

internal sealed record NodeStatusSnapshot(
    NodeRunState State,
    string Message,
    string TailscaleIp = "",
    int Port = 0,
    string NodeId = "",
    string NodeName = "",
    string RuntimeVersion = "",
    bool BrainReady = false,
    string BrainModel = "",
    bool FfmpegReady = false,
    int ActiveTaskCount = 0,
    bool ManagedRuntime = false)
{
    public static NodeStatusSnapshot Initial { get; } = new(
        NodeRunState.Starting,
        "Initialisation de Jade Node…");
}
