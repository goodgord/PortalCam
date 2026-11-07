using NAudio.CoreAudioApi;
using NAudio.Wave;
using PortalCam.Core;

namespace PortalCam;

/// <summary>
/// Records what apps play into the Hi-Fi Cable and sends it to the Portal as 16-bit mono PCM.
/// Prefers the cable's recording side, which keeps delivering silence when nothing plays; falls back to loopback
/// capture of its playback side.
/// </summary>
internal sealed class SpeakerCapture : IDisposable
{
    private IWaveIn? _capture;
    private SpeakerSender? _sender;
    private WaveFormat? _format;
    private readonly object _gate = new();
    public string State { get; private set; } = "off";
    public int SampleRate { get; private set; }
    public event Action? Dropped;

    public async Task StartAsync(PortalEndpoint ep, CancellationToken ct)
    {
        Stop();
        var capDevice = AudioDevices.Find(DataFlow.Capture, AudioDevices.SpeakerCableCapture);
        var renderDevice = capDevice == null ? AudioDevices.Find(DataFlow.Render, AudioDevices.SpeakerCableRender) : null;
        if (capDevice == null && renderDevice == null) { State = "Hi-Fi Cable not installed"; Log.Info("Speaker: " + State); return; }

        IWaveIn capture = capDevice != null
            ? new WasapiCapture(capDevice, true, 20)
            : new WasapiLoopbackCapture(renderDevice!);
        _format = capture.WaveFormat;
        SampleRate = _format.SampleRate;

        var sender = new SpeakerSender(SampleRate, 1);
        await sender.ConnectAsync(ep.Host, ep.SpeakerPort, ct);
        lock (_gate) { _sender = sender; _capture = capture; }

        capture.DataAvailable += OnData;
        capture.RecordingStopped += (_, e) => { if (e.Exception != null) Log.Info($"Speaker capture stopped: {e.Exception.Message}"); };
        capture.StartRecording();
        State = $"sending {SampleRate} Hz from {(capDevice ?? renderDevice)!.FriendlyName}";
        Log.Info("Speaker: " + State);
    }

    private byte[] _mono = new byte[0];

    private void OnData(object? sender, WaveInEventArgs e)
    {
        SpeakerSender? s; WaveFormat? f;
        lock (_gate) { s = _sender; f = _format; }
        if (s == null || f == null || e.BytesRecorded == 0) return;

        int channels = f.Channels;
        int frames;
        if (f.Encoding == WaveFormatEncoding.IeeeFloat || (f.Encoding == WaveFormatEncoding.Extensible && f.BitsPerSample == 32))
        {
            frames = e.BytesRecorded / (4 * channels);
            if (_mono.Length < frames * 2) _mono = new byte[frames * 2];
            for (int i = 0; i < frames; i++)
            {
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += BitConverter.ToSingle(e.Buffer, (i * channels + c) * 4);
                var v = (int)(sum / channels * 32767f);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                _mono[2 * i] = (byte)v; _mono[2 * i + 1] = (byte)(v >> 8);
            }
        }
        else if (f.BitsPerSample == 16)
        {
            frames = e.BytesRecorded / (2 * channels);
            if (_mono.Length < frames * 2) _mono = new byte[frames * 2];
            for (int i = 0; i < frames; i++)
            {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += BitConverter.ToInt16(e.Buffer, (i * channels + c) * 2);
                var v = sum / channels;
                _mono[2 * i] = (byte)v; _mono[2 * i + 1] = (byte)(v >> 8);
            }
        }
        else { Log.Info($"Speaker: unsupported capture format {f}"); return; }

        if (!s.Write(_mono.AsSpan(0, frames * 2)))
        {
            Log.Info("Speaker: connection to the Portal dropped");
            Stop();
            Dropped?.Invoke();
        }
    }

    public void Stop()
    {
        IWaveIn? c; SpeakerSender? s;
        lock (_gate) { c = _capture; s = _sender; _capture = null; _sender = null; }
        try { if (c != null) { c.DataAvailable -= OnData; c.StopRecording(); c.Dispose(); } } catch { }
        s?.Dispose();
        State = "off";
    }

    public void Dispose() => Stop();
}
