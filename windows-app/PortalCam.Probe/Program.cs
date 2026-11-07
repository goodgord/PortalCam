using PortalCam.Core;

// A console check of the network side, runnable on any OS:
//   PortalCam.Probe [host]        find the Portal (or use host), pull 3 s of video and audio, play a 2 s tone on it
Log.Line += Console.Error.WriteLine;
var fixedHost = args.Length > 0 ? args[0] : null;
var discovery = new Discovery(Adb.Find(AppContext.BaseDirectory), fixedHost);
Console.WriteLine("Looking for a Portal...");
var ep = await discovery.FindAsync(TimeSpan.FromSeconds(6), CancellationToken.None);
if (ep == null) { Console.WriteLine("No Portal found."); return 1; }
Console.WriteLine($"Found {ep}: {ep.StreamUrl}");

var status = await PortalControl.GetStatusAsync(ep, CancellationToken.None);
Console.WriteLine($"Status: {status}");

using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
var video = new MjpegClient();
long bytes = 0; video.Frame += f => bytes += f.Length;
var audio = new WavStreamClient();
audio.Pcm += (b, n) => { };
try { await Task.WhenAll(video.RunAsync(ep.StreamUrl, cts.Token), audio.RunAsync(ep.AudioUrl, cts.Token)); }
catch (OperationCanceledException) { }
Console.WriteLine($"Video: {video.FramesReceived} frames, {bytes / 1024} KB in 3 s ({video.FramesReceived / 3.0:F0} fps)");
Console.WriteLine($"Audio: {audio.SampleRate} Hz x{audio.Channels}, {audio.BytesReceived} bytes in 3 s ({audio.BytesReceived / 3.0 / (audio.SampleRate * 2.0 * Math.Max(1, audio.Channels)):P0} of real time)");

Console.WriteLine("Playing a 2 s tone through the Portal speaker...");
using var speaker = new SpeakerSender(48000, 1);
try
{
    await speaker.ConnectAsync(ep.Host, ep.SpeakerPort, CancellationToken.None);
    var chunk = new byte[48000 * 2 / 50];
    double phase = 0;
    for (int i = 0; i < 100 && speaker.Connected; i++)
    {
        for (int s = 0; s < chunk.Length / 2; s++)
        {
            var v = (short)(Math.Sin(phase) * 8000); phase += 2 * Math.PI * 440 / 48000;
            chunk[2 * s] = (byte)v; chunk[2 * s + 1] = (byte)(v >> 8);
        }
        speaker.Write(chunk);
        await Task.Delay(20);
    }
    Console.WriteLine($"Sent {speaker.BytesSent} bytes to the speaker.");
}
catch (Exception e) { Console.WriteLine($"Speaker: {e.Message}"); }
return 0;
