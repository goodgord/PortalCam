using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using PortalCam.Core;

namespace PortalCam;

/// <summary>
/// Finds the two virtual cables and can make them the Windows defaults.
///   Microphone path: PortalCam plays the Portal's mic into "CABLE Input"; apps record from "CABLE Output".
///   Speaker path:    apps play to "Hi-Fi Cable Input"; PortalCam records "Hi-Fi Cable Output" and sends it to the Portal.
/// </summary>
internal static class AudioDevices
{
    public const string MicCableRender = "CABLE Input";        // VB-Audio Virtual Cable, playback side
    public const string MicCableCapture = "CABLE Output";      // VB-Audio Virtual Cable, recording side
    public const string SpeakerCableRender = "Hi-Fi Cable Input";
    public const string SpeakerCableCapture = "Hi-Fi Cable Output";

    public static MMDevice? Find(DataFlow flow, string namePrefix)
    {
        using var en = new MMDeviceEnumerator();
        foreach (var d in en.EnumerateAudioEndPoints(flow, DeviceState.Active))
        {
            if (d.FriendlyName.StartsWith(namePrefix, StringComparison.Ordinal)) return d;
        }
        return null;
    }

    public static string Describe()
    {
        var mic = Find(DataFlow.Render, MicCableRender) != null;
        var spk = Find(DataFlow.Render, SpeakerCableRender) != null;
        return $"VB-Cable {(mic ? "found" : "MISSING")}, Hi-Fi Cable {(spk ? "found" : "MISSING")}";
    }

    /// <summary>Makes "Hi-Fi Cable Input" the default speaker and "CABLE Output" the default microphone, for every role.</summary>
    public static string SetDefaults()
    {
        var speaker = Find(DataFlow.Render, SpeakerCableRender);
        var mic = Find(DataFlow.Capture, MicCableCapture);
        var notes = new List<string>();
        if (speaker == null) notes.Add("Hi-Fi Cable is not installed, speaker default left alone");
        if (mic == null) notes.Add("VB-Cable is not installed, microphone default left alone");
        try
        {
            var policy = (IPolicyConfig)new PolicyConfigClient();
            foreach (var role in new[] { ERole.eConsole, ERole.eMultimedia, ERole.eCommunications })
            {
                if (speaker != null) policy.SetDefaultEndpoint(speaker.ID, role);
                if (mic != null) policy.SetDefaultEndpoint(mic.ID, role);
            }
            if (speaker != null) notes.Add("Default speaker is now Hi-Fi Cable Input (sound goes to the Portal)");
            if (mic != null) notes.Add("Default microphone is now CABLE Output (the Portal's microphone)");
        }
        catch (Exception e) { notes.Add($"Could not set defaults: {e.Message}"); }
        var text = string.Join(". ", notes);
        Log.Info(text);
        return text;
    }

    // Undocumented but long-stable Windows interface used by every "set default audio device" tool.
    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
    private class PolicyConfigClient { }

    private enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }

    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPolicyConfig
    {
        [PreserveSig] int GetMixFormat(string deviceId, IntPtr format);
        [PreserveSig] int GetDeviceFormat(string deviceId, bool isDefault, IntPtr format);
        [PreserveSig] int ResetDeviceFormat(string deviceId);
        [PreserveSig] int SetDeviceFormat(string deviceId, IntPtr endpointFormat, IntPtr mixFormat);
        [PreserveSig] int GetProcessingPeriod(string deviceId, bool isDefault, IntPtr defaultPeriod, IntPtr minimumPeriod);
        [PreserveSig] int SetProcessingPeriod(string deviceId, IntPtr period);
        [PreserveSig] int GetShareMode(string deviceId, IntPtr mode);
        [PreserveSig] int SetShareMode(string deviceId, IntPtr mode);
        [PreserveSig] int GetPropertyValue(string deviceId, bool bFxStore, IntPtr key, IntPtr value);
        [PreserveSig] int SetPropertyValue(string deviceId, bool bFxStore, IntPtr key, IntPtr value);
        [PreserveSig] int SetDefaultEndpoint(string deviceId, ERole role);
        [PreserveSig] int SetEndpointVisibility(string deviceId, bool isVisible);
    }
}
