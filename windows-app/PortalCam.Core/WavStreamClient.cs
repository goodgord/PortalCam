namespace PortalCam.Core;

/// <summary>Reads the Portal's live WAV stream and raises 16-bit PCM blocks as they arrive.</summary>
public sealed class WavStreamClient
{
    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    public int SampleRate { get; private set; }
    public int Channels { get; private set; }
    public event Action<byte[], int>? Pcm;
    public long BytesReceived { get; private set; }

    public async Task RunAsync(string url, CancellationToken ct)
    {
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        var header = new byte[44];
        await stream.ReadExactlyAsync(header, ct);
        if (System.Text.Encoding.ASCII.GetString(header, 0, 4) != "RIFF") throw new InvalidDataException("not a WAV stream");
        Channels = BitConverter.ToInt16(header, 22);
        SampleRate = BitConverter.ToInt32(header, 24);
        var bits = BitConverter.ToInt16(header, 34);
        if (bits != 16) throw new InvalidDataException($"unexpected {bits}-bit audio");
        var buf = new byte[SampleRate * Channels * 2 / 50]; // 20 ms
        while (!ct.IsCancellationRequested)
        {
            var n = await stream.ReadAsync(buf, ct);
            if (n <= 0) return;
            if ((n & 1) == 1) { // keep sample alignment
                var extra = new byte[1]; await stream.ReadExactlyAsync(extra, ct);
                var fixedBuf = new byte[n + 1]; Buffer.BlockCopy(buf, 0, fixedBuf, 0, n); fixedBuf[n] = extra[0];
                BytesReceived += n + 1; Pcm?.Invoke(fixedBuf, n + 1); continue;
            }
            BytesReceived += n;
            Pcm?.Invoke(buf, n);
        }
    }
}
