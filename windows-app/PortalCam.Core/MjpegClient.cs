using System.Text;

namespace PortalCam.Core;

/// <summary>Reads a multipart/x-mixed-replace MJPEG stream and raises one event per JPEG frame.</summary>
public sealed class MjpegClient
{
    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    public event Action<byte[]>? Frame;
    public long FramesReceived { get; private set; }

    public async Task RunAsync(string url, CancellationToken ct)
    {
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        var reader = new BufferedStreamReader(stream);
        while (!ct.IsCancellationRequested)
        {
            // Part headers end at a blank line; find Content-Length.
            int length = -1;
            while (true)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line == null) return;
                if (line.Length == 0) { if (length >= 0) break; else continue; }
                if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
                    length = int.Parse(line.AsSpan(15).Trim());
            }
            var jpeg = await reader.ReadExactlyAsync(length, ct);
            if (jpeg == null) return;
            FramesReceived++;
            Frame?.Invoke(jpeg);
        }
    }

    /// <summary>Line and block reads over one buffer, because the standard readers either buffer too eagerly or not at all.</summary>
    private sealed class BufferedStreamReader(Stream stream)
    {
        private readonly byte[] _buf = new byte[256 * 1024];
        private int _start, _end;

        private async Task<bool> FillAsync(CancellationToken ct)
        {
            if (_start > 0) { Buffer.BlockCopy(_buf, _start, _buf, 0, _end - _start); _end -= _start; _start = 0; }
            if (_end == _buf.Length) throw new InvalidDataException("header line too long");
            var n = await stream.ReadAsync(_buf.AsMemory(_end), ct);
            if (n <= 0) return false;
            _end += n;
            return true;
        }

        public async Task<string?> ReadLineAsync(CancellationToken ct)
        {
            while (true)
            {
                var span = _buf.AsSpan(_start, _end - _start);
                var nl = span.IndexOf((byte)'\n');
                if (nl >= 0)
                {
                    var len = nl > 0 && span[nl - 1] == '\r' ? nl - 1 : nl;
                    var line = Encoding.ASCII.GetString(span[..len]);
                    _start += nl + 1;
                    return line;
                }
                if (!await FillAsync(ct)) return null;
            }
        }

        public async Task<byte[]?> ReadExactlyAsync(int count, CancellationToken ct)
        {
            var result = new byte[count];
            int copied = 0;
            while (copied < count)
            {
                if (_start == _end && !await FillAsync(ct)) return null;
                var take = Math.Min(count - copied, _end - _start);
                Buffer.BlockCopy(_buf, _start, result, copied, take);
                _start += take; copied += take;
            }
            return result;
        }
    }
}
