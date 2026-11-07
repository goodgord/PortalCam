using PortalCam;
using PortalCam.Core;

// PortalCam for Windows. Runs in the tray. Command line:
//   PortalCam.exe                     start (or bring up the running copy)
//   PortalCam.exe --set-defaults      make the Portal the default speaker and microphone, then exit
//   PortalCam.exe --startup on|off    start with Windows, then exit
//   PortalCam.exe --host 192.168.1.5  use this Portal address if discovery fails
Log.FilePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PortalCam", "portalcam.log");
Directory.CreateDirectory(Path.GetDirectoryName(Log.FilePath)!);

string? fixedHost = null;
for (int i = 0; i < args.Length; i++)
{
    switch (args[i])
    {
        case "--set-defaults":
            Console.WriteLine(AudioDevices.SetDefaults());
            return 0;
        case "--startup":
            Startup.Set(i + 1 < args.Length && args[i + 1] != "off");
            Console.WriteLine("Start with Windows: " + (Startup.IsEnabled() ? "on" : "off"));
            return 0;
        case "--host":
            fixedHost = i + 1 < args.Length ? args[++i] : null;
            break;
    }
}

using var single = new Mutex(true, "PortalCam.SingleInstance", out var first);
if (!first)
{
    MessageBox.Show("PortalCam is already running. Look for its icon in the tray by the clock.", "PortalCam", MessageBoxButtons.OK, MessageBoxIcon.Information);
    return 0;
}

Log.Info($"PortalCam {typeof(Program).Assembly.GetName().Version} starting. {AudioDevices.Describe()}");
ApplicationConfiguration.Initialize();
Application.Run(new TrayApp(fixedHost));
return 0;

internal static partial class Program { }
