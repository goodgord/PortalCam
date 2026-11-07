using NAudio.CoreAudioApi;
using PortalCam.Core;

namespace PortalCam;

/// <summary>
/// Keeps the Portal connected. Finds it, runs the three streams (video to the virtual camera, Portal mic to the
/// VB-Cable, Hi-Fi Cable to the Portal speaker), and starts over when anything drops.
/// </summary>
internal sealed class Session : IDisposable
{
    private readonly VirtualCamera _camera;
    private readonly MicOutput _mic = new();
    private readonly SpeakerCapture _speaker = new();
    private readonly Discovery _discovery;
    private readonly CancellationTokenSource _cts = new();
    private CancellationTokenSource? _streamCts;

    public PortalEndpoint? Portal { get; private set; }
    public string State { get; private set; } = "Starting";
    public double Fps { get; private set; }
    public event Action? Changed;

    public Session(VirtualCamera camera, Adb? adb, string? fixedHost)
    {
        _camera = camera;
        _discovery = new Discovery(adb, fixedHost);
        _speaker.Dropped += () => _streamCts?.Cancel();
    }

    public void Start() => _ = Task.Run(LoopAsync);

    private void Set(string state)
    {
        if (State != state) Log.Info(state);
        State = state;
        _camera.SetMessage(state);
        Changed?.Invoke();
    }

    private async Task LoopAsync()
    {
        var ct = _cts.Token;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                Set("Looking for the Portal...");
                var ep = await _discovery.FindAsync(TimeSpan.FromSeconds(4), ct);
                if (ep == null) { await Task.Delay(1500, ct); continue; }
                Portal = ep;
                Set($"Connected to {ep.Name} via {ep.Via}");
                await RunStreamsAsync(ep, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
            catch (Exception e) { Log.Info($"session: {e.Message}"); }
            Portal = null;
            Fps = 0;
            _mic.Stop();
            _speaker.Stop();
            if (!ct.IsCancellationRequested) { Set("Portal connection lost, reconnecting..."); await Task.Delay(1500, ct); }
        }
    }

    private async Task RunStreamsAsync(PortalEndpoint ep, CancellationToken outer)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(outer);
        _streamCts = cts;
        var ct = cts.Token;

        var video = new MjpegClient();
        long frames = 0; var window = DateTime.UtcNow;
        video.Frame += jpeg =>
        {
            _camera.SendJpeg(jpeg);
            frames++;
            var now = DateTime.UtcNow;
            if ((now - window).TotalSeconds >= 2) { Fps = frames / (now - window).TotalSeconds; frames = 0; window = now; Changed?.Invoke(); }
        };

        var audio = new WavStreamClient();
        bool micStarted = false;
        audio.Pcm += (buf, n) =>
        {
            if (!micStarted) { micStarted = true; try { _mic.Start(audio.SampleRate, audio.Channels); } catch (Exception e) { Log.Info($"mic output: {e.Message}"); } }
            _mic.Write(buf, n);
        };

        var speakerTask = Task.Run(async () =>
        {
            try { await _speaker.StartAsync(ep, ct); }
            catch (Exception e) { Log.Info($"speaker: {e.Message}"); }
        }, ct);

        var videoTask = video.RunAsync(ep.StreamUrl, ct);
        var audioTask = audio.RunAsync(ep.AudioUrl, ct);
        // Whichever stream ends first ends the session; the loop reconnects everything together.
        var finished = await Task.WhenAny(videoTask, audioTask);
        cts.Cancel();
        try { await finished; } catch (Exception e) when (e is not OperationCanceledException) { Log.Info($"stream ended: {e.Message}"); }
        try { await Task.WhenAll(videoTask, audioTask, speakerTask); } catch { }
        _streamCts = null;
    }

    public async Task<bool> SetPortalVolumeAsync(int percent)
    {
        var ep = Portal;
        return ep != null && await PortalControl.SetVolumeAsync(ep, percent, _cts.Token);
    }

    public string MicState => _mic.State;
    public string SpeakerState => _speaker.State;

    public void Dispose()
    {
        _cts.Cancel();
        _streamCts?.Cancel();
        _mic.Dispose();
        _speaker.Dispose();
    }
}
