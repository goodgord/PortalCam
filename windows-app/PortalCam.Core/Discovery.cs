using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace PortalCam.Core;

/// <summary>
/// Finds a Portal. USB first (adb present and a device attached), then the UDP beacon the Portal app broadcasts,
/// then a unicast probe to a fixed address if one was configured.
/// </summary>
public sealed class Discovery
{
    public const int BeaconPort = 18475;
    private readonly Adb? _adb;
    private readonly string? _fixedHost;
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(3) };

    public Discovery(Adb? adb, string? fixedHost)
    {
        _adb = adb;
        _fixedHost = fixedHost;
    }

    public async Task<PortalEndpoint?> FindAsync(TimeSpan wait, CancellationToken ct)
    {
        var usb = await TryUsbAsync(ct);
        if (usb != null) return usb;

        if (!string.IsNullOrWhiteSpace(_fixedHost))
        {
            var fixedEp = new PortalEndpoint(_fixedHost!, 8080, 8081, "fixed address", "Portal");
            if (await IsAliveAsync(fixedEp, ct)) return fixedEp;
        }

        return await ListenForBeaconAsync(wait, ct);
    }

    private async Task<PortalEndpoint?> TryUsbAsync(CancellationToken ct)
    {
        if (_adb == null) return null;
        try
        {
            var (serial, note) = await _adb.FirstDeviceAsync();
            if (serial == null) { if (note != null) Log.Info(note); return null; }
            await _adb.ForwardAsync(serial, 8080, 8081);
            var ep = new PortalEndpoint("127.0.0.1", Adb.LocalVideoPort, Adb.LocalSpeakerPort, "USB", "Portal");
            if (!await IsAliveAsync(ep, ct))
            {
                Log.Info("Portal on USB but PortalCam not running on it; starting it");
                await _adb.StartPortalAppAsync(serial);
                await Task.Delay(2500, ct);
                if (!await IsAliveAsync(ep, ct)) return null;
            }
            return ep;
        }
        catch (Exception e) { Log.Info($"adb: {e.Message}"); return null; }
    }

    public static async Task<bool> IsAliveAsync(PortalEndpoint ep, CancellationToken ct)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(2500);
            using var doc = await Http.GetFromJsonAsync<JsonDocument>(ep.StatusUrl, cts.Token);
            return doc != null && doc.RootElement.TryGetProperty("width", out _);
        }
        catch { return false; }
    }

    private async Task<PortalEndpoint?> ListenForBeaconAsync(TimeSpan wait, CancellationToken ct)
    {
        using var udp = new UdpClient(AddressFamily.InterNetwork);
        udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        udp.EnableBroadcast = true;
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, BeaconPort));

        // Also ask, for networks that drop broadcasts but pass a reply.
        var probe = Encoding.ASCII.GetBytes("PORTALCAM?");
        try { await udp.SendAsync(probe, probe.Length, new IPEndPoint(IPAddress.Broadcast, BeaconPort)); } catch { }

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(wait);
        try
        {
            while (!cts.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(cts.Token);
                var ep = Parse(result.Buffer, result.RemoteEndPoint.Address);
                if (ep != null) return ep;
            }
        }
        catch (OperationCanceledException) { }
        return null;
    }

    internal static PortalEndpoint? Parse(byte[] data, IPAddress from)
    {
        try
        {
            using var doc = JsonDocument.Parse(data);
            var root = doc.RootElement;
            if (!root.TryGetProperty("service", out var svc) || svc.GetString() != "portalcam") return null;
            // Trust the sender's address over the one it claims, since the Portal may have several.
            var host = from.ToString();
            var video = root.TryGetProperty("video", out var v) ? v.GetInt32() : 8080;
            var speaker = root.TryGetProperty("speaker", out var s) ? s.GetInt32() : 8081;
            var name = root.TryGetProperty("name", out var n) ? n.GetString() ?? "Portal" : "Portal";
            return new PortalEndpoint(host, video, speaker, "Wi-Fi", name);
        }
        catch { return null; }
    }
}
