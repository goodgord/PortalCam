namespace PortalCam.Core;

/// <summary>A small log to a file next to the program, so a failure on the day leaves a trace.</summary>
public static class Log
{
    private static readonly object Gate = new();
    public static string? FilePath;
    public static event Action<string>? Line;

    public static void Info(string message)
    {
        var line = $"{DateTime.Now:HH:mm:ss} {message}";
        Line?.Invoke(line);
        if (FilePath == null) return;
        lock (Gate)
        {
            try
            {
                var info = new FileInfo(FilePath);
                if (info.Exists && info.Length > 2_000_000) info.Delete();
                File.AppendAllText(FilePath, line + Environment.NewLine);
            }
            catch { }
        }
    }
}
