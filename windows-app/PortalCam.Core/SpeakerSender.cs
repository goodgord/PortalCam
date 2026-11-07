using System.Net.Sockets;
using System.Text;

namespace PortalCam.Core;

/// <summary>Sends 16-bit PCM to the Portal's speaker port. The first line tells the Portal the format.</summary>
public sealed class SpeakerSender : IDisposable
{
    private TcpClient? _client;
    private NetworkStream? _stream;
    public int SampleRate { get; }
    public int Channels { get; }
    public long BytesSent { get; private set; }
    public bool Connected => _client?.Connected == true;

    public SpeakerSender(int sampleRate, int channels)
    {
        SampleRate = sampleRate;
        Channels = channels;
    }

    public async Task ConnectAsync(string host, int port, CancellationToken ct)
    {
        var client = new TcpClient { NoDelay = true, SendBufferSize = 64 * 1024 };
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(4000);
        await client.ConnectAsync(host, port, cts.Token);
        _client = client;
        _stream = client.GetStream();
        var header = Encoding.ASCII.GetBytes($"PCM {SampleRate} {Channels} 16\n");
        await _stream.WriteAsync(header, ct);
    }

    /// <summary>Writes a block of PCM. Returns false if the connection is gone.</summary>
    public bool Write(ReadOnlySpan<byte> pcm)
    {
        var s = _stream;
        if (s == null) return false;
        try { s.Write(pcm); BytesSent += pcm.Length; return true; }
        catch { Dispose(); return false; }
    }

    public void Dispose()
    {
        try { _stream?.Dispose(); } catch { }
        try { _client?.Dispose(); } catch { }
        _stream = null; _client = null;
    }
}
