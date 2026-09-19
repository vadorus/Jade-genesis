using System.Net;

namespace JadeNode.Core;

public static class TailscaleAddress
{
    public static bool IsTailscaleIpv4(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && bytes[0] == 100 && bytes[1] is >= 64 and <= 127;
    }

    public static string? SelectIpv4(IEnumerable<string> candidates)
    {
        foreach (var candidate in candidates)
        {
            if (IPAddress.TryParse(candidate?.Trim(), out var parsed) && IsTailscaleIpv4(parsed))
            {
                return parsed.ToString();
            }
        }

        return null;
    }
}
