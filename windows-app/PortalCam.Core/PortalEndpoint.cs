namespace PortalCam.Core;

/// <summary>Where a Portal can be reached. Over USB the host is the PC itself and the ports are the adb forwards.</summary>
public sealed record PortalEndpoint(string Host, int VideoPort, int SpeakerPort, string Via, string Name)
{
    public string VideoBase => $"http://{Host}:{VideoPort}";
    public string StreamUrl => $"{VideoBase}/stream";
    public string AudioUrl => $"{VideoBase}/audio.wav";
    public string StatusUrl => $"{VideoBase}/status";
    public override string ToString() => $"{Name} at {Host} via {Via}";
}
