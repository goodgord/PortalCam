using System.Diagnostics;
using System.Drawing;
using PortalCam.Core;

namespace PortalCam;

/// <summary>The tray icon and its menu. Everything the user can do lives here.</summary>
internal sealed class TrayApp : ApplicationContext
{
    private readonly NotifyIcon _icon;
    private readonly VirtualCamera _camera;
    private readonly Session _session;
    private readonly ToolStripMenuItem _status = new() { Enabled = false };
    private readonly ToolStripMenuItem _detail = new() { Enabled = false };
    private readonly ToolStripMenuItem _devices = new() { Enabled = false };
    private readonly ToolStripMenuItem _startup = new("Start with Windows") { CheckOnClick = true };
    private readonly System.Windows.Forms.Timer _refresh = new() { Interval = 1000 };
    private readonly Icon _iconOn, _iconOff;
    private bool _wasConnected;

    public TrayApp(string? fixedHost)
    {
        _iconOn = MakeIcon(Color.FromArgb(46, 160, 67));
        _iconOff = MakeIcon(Color.FromArgb(120, 120, 120));
        _camera = new VirtualCamera();
        _session = new Session(_camera, Adb.Find(AppContext.BaseDirectory), fixedHost);

        var menu = new ContextMenuStrip();
        menu.Items.Add(_status);
        menu.Items.Add(_detail);
        menu.Items.Add(_devices);
        menu.Items.Add(new ToolStripSeparator());
        var volume = new ToolStripMenuItem("Portal speaker volume");
        foreach (var pct in new[] { 40, 60, 80, 100 })
        {
            var p = pct;
            volume.DropDownItems.Add(new ToolStripMenuItem($"{p}%", null, async (_, _) => await _session.SetPortalVolumeAsync(p)));
        }
        menu.Items.Add(volume);
        menu.Items.Add(new ToolStripMenuItem("Use the Portal as the default speaker and microphone", null, (_, _) =>
            MessageBox.Show(AudioDevices.SetDefaults(), "PortalCam", MessageBoxButtons.OK, MessageBoxIcon.Information)));
        menu.Items.Add(new ToolStripMenuItem("Open the Portal's picture in a browser", null, (_, _) =>
        {
            var ep = _session.Portal; if (ep == null) return;
            Process.Start(new ProcessStartInfo(ep.VideoBase + "/") { UseShellExecute = true });
        }));
        _startup.Checked = Startup.IsEnabled();
        _startup.CheckedChanged += (_, _) => { try { Startup.Set(_startup.Checked); } catch (Exception e) { Log.Info($"startup: {e.Message}"); } };
        menu.Items.Add(_startup);
        menu.Items.Add(new ToolStripMenuItem("Open log file", null, (_, _) => { if (Log.FilePath != null && File.Exists(Log.FilePath)) Process.Start(new ProcessStartInfo(Log.FilePath) { UseShellExecute = true }); }));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Quit PortalCam", null, (_, _) => ExitThread()));

        _icon = new NotifyIcon { Icon = _iconOff, Text = "PortalCam", Visible = true, ContextMenuStrip = menu };
        _icon.DoubleClick += (_, _) => menu.Show(Cursor.Position);

        _session.Changed += () => { try { _icon.ContextMenuStrip?.BeginInvoke(Refresh); } catch { } };
        _refresh.Tick += (_, _) => Refresh();
        _refresh.Start();

        if (!Softcam.IsAvailable())
            _icon.ShowBalloonTip(8000, "PortalCam", "softcam.dll is missing next to PortalCam.exe. The camera will not appear in apps.", ToolTipIcon.Warning);

        _session.Start();
        Refresh();
    }

    private void Refresh()
    {
        var connected = _session.Portal != null;
        _status.Text = _session.State;
        _detail.Text = connected
            ? $"Video {_session.Fps:F0} fps   Camera {(_camera.AppConnected ? "in use by an app" : "idle")}"
            : "Camera shows a waiting message until the Portal is found";
        _devices.Text = $"Mic: {_session.MicState}   Speaker: {_session.SpeakerState}";
        _icon.Icon = connected ? _iconOn : _iconOff;
        _icon.Text = Truncate($"PortalCam: {_session.State}", 63);
        if (connected != _wasConnected)
        {
            _wasConnected = connected;
            _icon.ShowBalloonTip(3000, "PortalCam", connected ? $"Connected to the Portal via {_session.Portal!.Via}. Camera, microphone and speaker are ready." : "Lost the Portal. Looking for it again.", ToolTipIcon.Info);
        }
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s[..(max - 1)] + "…";

    private static Icon MakeIcon(Color colour)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(colour);
            g.FillRoundedRectangle(brush, new Rectangle(2, 7, 28, 19), new Size(10, 10));
            g.FillEllipse(Brushes.White, 10, 10, 13, 13);
            using var lens = new SolidBrush(colour);
            g.FillEllipse(lens, 13, 13, 7, 7);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    protected override void ExitThreadCore()
    {
        _refresh.Stop();
        _icon.Visible = false;
        _session.Dispose();
        _camera.Dispose();
        base.ExitThreadCore();
    }
}
