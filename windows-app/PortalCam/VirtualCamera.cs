using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using PortalCam.Core;

namespace PortalCam;

/// <summary>
/// The camera Windows apps see. It is always 1280x720 and always exists while PortalCam runs, so Teams finds a
/// working camera whether or not the Portal is connected yet. Portal frames are drawn into it scaled to fit; before
/// the Portal connects it shows a message.
/// </summary>
internal sealed class VirtualCamera : IDisposable
{
    public const int Width = 1280, Height = 720;
    private IntPtr _camera;
    private readonly Bitmap _canvas = new(Width, Height, PixelFormat.Format24bppRgb);
    private readonly byte[] _bgr = new byte[Width * Height * 3];
    private readonly GCHandle _pin;
    private readonly object _gate = new();
    private DateTime _lastFrame = DateTime.MinValue;
    private readonly System.Threading.Timer _placeholderTimer;
    private string _message = "Waiting for the Portal...";
    public bool Created => _camera != IntPtr.Zero;
    public bool AppConnected => _camera != IntPtr.Zero && Softcam.scIsConnected(_camera);
    public int FramesSent { get; private set; }

    public VirtualCamera()
    {
        _pin = GCHandle.Alloc(_bgr, GCHandleType.Pinned);
        _camera = Softcam.scCreateCamera(Width, Height, 0f);
        if (_camera == IntPtr.Zero) Log.Info("softcam: could not create the virtual camera (is another PortalCam running?)");
        _placeholderTimer = new System.Threading.Timer(_ => PlaceholderTick(), null, 0, 500);
    }

    public void SetMessage(string message)
    {
        _message = message;
    }

    private void PlaceholderTick()
    {
        if ((DateTime.UtcNow - _lastFrame).TotalMilliseconds < 1500) return;
        lock (_gate)
        {
            using var g = Graphics.FromImage(_canvas);
            g.Clear(Color.FromArgb(30, 30, 34));
            using var font = new Font("Segoe UI", 36, FontStyle.Regular, GraphicsUnit.Pixel);
            using var small = new Font("Segoe UI", 24, FontStyle.Regular, GraphicsUnit.Pixel);
            var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("PortalCam", font, Brushes.White, new RectangleF(0, Height / 2f - 70, Width, 60), fmt);
            g.DrawString(_message, small, Brushes.LightGray, new RectangleF(0, Height / 2f, Width, 50), fmt);
            Push();
        }
    }

    /// <summary>Decodes a JPEG from the Portal and sends it as the next camera frame.</summary>
    public void SendJpeg(byte[] jpeg)
    {
        try
        {
            using var ms = new MemoryStream(jpeg, false);
            using var img = Image.FromStream(ms, false, false);
            lock (_gate)
            {
                using var g = Graphics.FromImage(_canvas);
                g.InterpolationMode = InterpolationMode.Bilinear;
                g.PixelOffsetMode = PixelOffsetMode.Half;
                g.CompositingMode = CompositingMode.SourceCopy;
                if (img.Width == Width && img.Height == Height)
                    g.DrawImage(img, new Rectangle(0, 0, Width, Height), 0, 0, img.Width, img.Height, GraphicsUnit.Pixel);
                else
                {
                    g.Clear(Color.Black);
                    var scale = Math.Min((float)Width / img.Width, (float)Height / img.Height);
                    var w = (int)(img.Width * scale); var h = (int)(img.Height * scale);
                    g.DrawImage(img, new Rectangle((Width - w) / 2, (Height - h) / 2, w, h), 0, 0, img.Width, img.Height, GraphicsUnit.Pixel);
                }
                _lastFrame = DateTime.UtcNow;
                Push();
            }
        }
        catch (Exception e) { Log.Info($"frame decode: {e.Message}"); }
    }

    /// <summary>Copies the canvas as top-down BGR24 (what softcam expects) and hands it over.</summary>
    private void Push()
    {
        if (_camera == IntPtr.Zero) { _camera = Softcam.scCreateCamera(Width, Height, 0f); if (_camera == IntPtr.Zero) return; }
        var data = _canvas.LockBits(new Rectangle(0, 0, Width, Height), ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
        try
        {
            var rowBytes = Width * 3;
            for (int y = 0; y < Height; y++)
                Marshal.Copy(data.Scan0 + y * data.Stride, _bgr, y * rowBytes, rowBytes);
        }
        finally { _canvas.UnlockBits(data); }
        Softcam.scSendFrame(_camera, _pin.AddrOfPinnedObject());
        FramesSent++;
    }

    public void Dispose()
    {
        _placeholderTimer.Dispose();
        lock (_gate)
        {
            if (_camera != IntPtr.Zero) { Softcam.scDeleteCamera(_camera); _camera = IntPtr.Zero; }
        }
        _pin.Free();
        _canvas.Dispose();
    }
}
