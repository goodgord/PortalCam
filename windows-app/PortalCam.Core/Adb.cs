using System.Diagnostics;

namespace PortalCam.Core;

/// <summary>Drives adb.exe when it sits next to the program, for a Portal on a USB cable.</summary>
public sealed class Adb
{
    public const int LocalVideoPort = 18080;
    public const int LocalSpeakerPort = 18081;
    public string ExePath { get; }

    public Adb(string exePath) => ExePath = exePath;

    public static Adb? Find(string baseDirectory)
    {
        foreach (var candidate in new[] { Path.Combine(baseDirectory, "adb.exe"), Path.Combine(baseDirectory, "platform-tools", "adb.exe"), Path.Combine(baseDirectory, "adb") })
            if (File.Exists(candidate)) return new Adb(candidate);
        return null;
    }

    public async Task<string> RunAsync(string args, int timeoutMs = 8000)
    {
        var psi = new ProcessStartInfo(ExePath, args)
        {
            RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("adb did not start");
        var stdout = p.StandardOutput.ReadToEndAsync();
        var stderr = p.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(timeoutMs);
        try { await p.WaitForExitAsync(cts.Token); }
        catch (OperationCanceledException) { try { p.Kill(true); } catch { } throw new TimeoutException($"adb {args} timed out"); }
        return (await stdout) + (await stderr);
    }

    /// <summary>Serial of the first device in the "device" state, or null. "unauthorized" means the Portal is waiting for a tap.</summary>
    public async Task<(string? serial, string? note)> FirstDeviceAsync()
    {
        var text = await RunAsync("devices");
        string? note = null;
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of") || line.StartsWith("*")) continue;
            var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2) continue;
            if (parts[1] == "device") return (parts[0], null);
            note = parts[1] == "unauthorized" ? "Tap Allow on the Portal's USB debugging prompt" : $"USB device is {parts[1]}";
        }
        return (null, note);
    }

    public async Task ForwardAsync(string serial, int remoteVideo, int remoteSpeaker)
    {
        await RunAsync($"-s {serial} forward tcp:{LocalVideoPort} tcp:{remoteVideo}");
        await RunAsync($"-s {serial} forward tcp:{LocalSpeakerPort} tcp:{remoteSpeaker}");
    }

    public Task StartPortalAppAsync(string serial) =>
        RunAsync($"-s {serial} shell am start-foreground-service -n au.gordo.portalcam/.CamService -a au.gordo.portalcam.START");
}
