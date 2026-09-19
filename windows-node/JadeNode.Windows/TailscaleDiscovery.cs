using System.Net.NetworkInformation;
using System.Net.Sockets;
using JadeNode.Core;

namespace JadeNode.Windows;

internal static class TailscaleDiscovery
{
    public static string? FindIpv4()
    {
        try
        {
            var candidates = NetworkInterface.GetAllNetworkInterfaces()
                .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
                .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
                .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(address => address.Address.ToString());
            return TailscaleAddress.SelectIpv4(candidates);
        }
        catch (NetworkInformationException)
        {
            return null;
        }
        catch (SocketException)
        {
            return null;
        }
    }
}
