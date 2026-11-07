using System.Runtime.InteropServices;

namespace PortalCam;

/// <summary>P/Invoke to softcam.dll (MIT, github.com/tshino/softcam), the DirectShow virtual camera filter.</summary>
internal static class Softcam
{
    private const string Dll = "softcam.dll";

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr scCreateCamera(int width, int height, float framerate);

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    public static extern void scDeleteCamera(IntPtr camera);

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    public static extern void scSendFrame(IntPtr camera, IntPtr imageBits);

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    [return: MarshalAs(UnmanagedType.I1)]
    public static extern bool scIsConnected(IntPtr camera);

    public static bool IsAvailable()
    {
        try { return NativeLibrary.TryLoad(Path.Combine(AppContext.BaseDirectory, Dll), out _); }
        catch { return false; }
    }
}
