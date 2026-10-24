using System.Net;
using System.Text;
using JadeNode.Core;

var tests = new (string Name, Func<Task> Run)[]
{
    ("Tailscale range", TestTailscaleRangeAsync),
    ("Node config", TestNodeConfigAsync),
    ("Runtime health", TestRuntimeHealthAsync),
    ("GitHub update gate", TestGitHubReleaseAsync),
    ("SHA-256 verification", TestSha256Async),
};

foreach (var test in tests)
{
    await test.Run();
    Console.WriteLine($"PASS {test.Name}");
}

Console.WriteLine($"JadeNode.Core: {tests.Length} tests passed.");

static Task TestTailscaleRangeAsync()
{
    Assert(TailscaleAddress.IsTailscaleIpv4(IPAddress.Parse("100.64.0.0")), "lower boundary");
    Assert(TailscaleAddress.IsTailscaleIpv4(IPAddress.Parse("100.127.255.255")), "upper boundary");
    Assert(!TailscaleAddress.IsTailscaleIpv4(IPAddress.Parse("100.63.255.255")), "below range");
    Assert(!TailscaleAddress.IsTailscaleIpv4(IPAddress.Parse("192.168.1.41")), "LAN rejected");
    Assert(TailscaleAddress.SelectIpv4(new[] { "192.168.1.41", "100.98.238.6" }) == "100.98.238.6", "selection");
    return Task.CompletedTask;
}

static async Task TestNodeConfigAsync()
{
    var path = Path.Combine(Path.GetTempPath(), $"jade-node-config-{Guid.NewGuid():N}.json");
    try
    {
        await File.WriteAllTextAsync(path, """
            {"node_id":"pc-test","node_name":"PC test","token":"top-secret","port":8765}
            """);
        Assert(NodeAgentConfig.TryLoad(path, out var config, out var error), error);
        Assert(config is not null, "config null");
        Assert(config!.NodeId == "pc-test", "node id");
        Assert(config.Port == 8765, "port");
        Assert(!config.ToString().Contains("top-secret", StringComparison.Ordinal), "token leaked by ToString");
    }
    finally
    {
        File.Delete(path);
    }
}

static Task TestRuntimeHealthAsync()
{
    const string json = """
        {
          "protocol":"jade-genesis-node/0.0.6",
          "agent_version":"0.1.9",
          "bind_address":"100.98.238.6",
          "node_id":"pc-test",
          "name":"PC test",
          "brain_ready":true,
          "brain_model":"qwen3:4b",
          "ffmpeg_transcode_probe":{"ready":true}
        }
        """;
    Assert(RuntimeHealth.TryParse(json, out var health, out var error), error);
    Assert(health is { BrainReady: true, FfmpegReady: true, BindAddress: "100.98.238.6" }, "health flags");
    Assert(!RuntimeHealth.TryParse("{\"protocol\":\"wrong\"}", out _, out _), "bad protocol accepted");
    return Task.CompletedTask;
}

static Task TestGitHubReleaseAsync()
{
    var sha = new string('a', 64);
    var valid = $$"""
        {
          "draft":false,
          "prerelease":false,
          "tag_name":"jade-node-v0.1.1",
          "assets":[{
            "name":"JadeNode.exe",
            "size":123456,
            "digest":"sha256:{{sha}}",
            "browser_download_url":"https://github.com/vadorus/Jade-genesis/releases/download/jade-node-v0.1.1/JadeNode.exe"
          }]
        }
        """;
    Assert(GitHubRelease.TryParseLatest(valid, out var release, out var error), error);
    Assert(release?.Version == new Version(0, 1, 1), "version");
    Assert(GitHubRelease.TryParseAvailable($"[{valid}]", out var available, out error), error);
    Assert(available?.Tag == "jade-node-v0.1.1", "release list selection");

    var hostile = valid.Replace("github.com/vadorus/Jade-genesis", "example.com/vadorus/Jade-genesis", StringComparison.Ordinal);
    Assert(!GitHubRelease.TryParseLatest(hostile, out _, out _), "foreign host accepted");
    var unsigned = valid.Replace($"sha256:{sha}", string.Empty, StringComparison.Ordinal);
    Assert(!GitHubRelease.TryParseLatest(unsigned, out _, out _), "missing digest accepted");
    return Task.CompletedTask;
}

static async Task TestSha256Async()
{
    var payload = Encoding.UTF8.GetBytes("jade-node-update");
    await using var validStream = new MemoryStream(payload);
    Assert(
        await GitHubRelease.VerifySha256Async(
            validStream,
            "1dbe987b1096cc5366f0896d0e3edd1ee3a42fa0144b608c06058cf4f9da31cb"),
        "valid digest rejected");

    await using var invalidStream = new MemoryStream(payload);
    Assert(!await GitHubRelease.VerifySha256Async(invalidStream, new string('0', 64)), "bad digest accepted");
}

static void Assert(bool condition, string message)
{
    if (!condition)
    {
        throw new InvalidOperationException(message);
    }
}
