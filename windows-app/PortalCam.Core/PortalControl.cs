using System.Net.Http.Json;
using System.Text.Json;

namespace PortalCam.Core;

/// <summary>Reads the Portal's status line and sets its speaker volume.</summary>
public static class PortalControl
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(3) };

    public sealed record Status(int Width, int Height, double Fps, int Clients, string Mic, string Speaker, int Volume);

    public static async Task<Status?> GetStatusAsync(PortalEndpoint ep, CancellationToken ct)
    {
        try
        {
            using var doc = await Http.GetFromJsonAsync<JsonDocument>(ep.StatusUrl, ct);
            if (doc == null) return null;
            var r = doc.RootElement;
            int I(string k) => r.TryGetProperty(k, out var v) && v.TryGetInt32(out var i) ? i : 0;
            string S(string k) => r.TryGetProperty(k, out var v) ? v.GetString() ?? "" : "";
            return new Status(I("width"), I("height"), r.TryGetProperty("fps", out var f) ? f.GetDouble() : 0, I("clients"), S("mic"), S("speaker"), I("volume"));
        }
        catch { return null; }
    }

    public static async Task<bool> SetVolumeAsync(PortalEndpoint ep, int percent, CancellationToken ct)
    {
        try { using var r = await Http.GetAsync($"{ep.VideoBase}/volume?percent={percent}", ct); return r.IsSuccessStatusCode; }
        catch { return false; }
    }
}
