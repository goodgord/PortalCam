using NAudio.CoreAudioApi;
using NAudio.Wave;
using PortalCam.Core;

namespace PortalCam;

/// <summary>Plays the Portal's microphone into the VB-Cable, which apps see as a microphone.</summary>
internal sealed class MicOutput : IDisposable
{
    private WasapiOut? _out;
    private BufferedWaveProvider? _buffer;
    public string State { get; private set; } = "off";

    public void Start(int sampleRate, int channels)
    {
        Stop();
        var device = AudioDevices.Find(DataFlow.Render, AudioDevices.MicCableRender);
        if (device == null) { State = "VB-Cable not installed"; Log.Info("Mic: " + State); return; }
        _buffer = new BufferedWaveProvider(new WaveFormat(sampleRate, 16, channels))
        {
            BufferDuration = TimeSpan.FromSeconds(2),
            DiscardOnBufferOverflow = true,
            ReadFully = true, // silence when the Portal is quiet, so the cable keeps flowing
        };
        _out = new WasapiOut(device, AudioClientShareMode.Shared, true, 40);
        _out.Init(_buffer);
        _out.Play();
        State = $"playing into {device.FriendlyName}";
        Log.Info("Mic: " + State);
    }

    public void Write(byte[] pcm, int count)
    {
        var b = _buffer;
        if (b == null) return;
        // Keep the delay short: if more than 250 ms has piled up, drop back to about 60 ms.
        if (b.BufferedDuration.TotalMilliseconds > 250)
        {
            b.ClearBuffer();
        }
        b.AddSamples(pcm, 0, count);
    }

    public void Stop()
    {
        try { _out?.Stop(); _out?.Dispose(); } catch { }
        _out = null; _buffer = null;
        State = "off";
    }

    public void Dispose() => Stop();
}
