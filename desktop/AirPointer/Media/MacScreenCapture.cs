using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace AirPointer.Media;

/// <summary>
/// CoreGraphics capture of the display under the cursor. Needs the Screen Recording permission
/// (System Settings → Privacy &amp; Security); without it macOS returns only the wallpaper.
/// </summary>
[SupportedOSPlatform("macos")]
internal sealed class MacScreenCapture : IScreenCapture
{
    private const string CG = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";
    private const string CF = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    [StructLayout(LayoutKind.Sequential)]
    private struct CGPoint { public double X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct CGRect { public double X, Y, W, H; }

    [DllImport(CG)] private static extern IntPtr CGEventCreate(IntPtr source);
    [DllImport(CG)] private static extern CGPoint CGEventGetLocation(IntPtr ev);
    [DllImport(CG)] private static extern int CGGetDisplaysWithPoint(CGPoint point, uint max, out uint display, out uint count);
    [DllImport(CG)] private static extern uint CGMainDisplayID();
    [DllImport(CG)] private static extern CGRect CGDisplayBounds(uint display);
    [DllImport(CG)] private static extern IntPtr CGDisplayCreateImage(uint display);
    [DllImport(CG)] private static extern nuint CGImageGetWidth(IntPtr image);
    [DllImport(CG)] private static extern nuint CGImageGetHeight(IntPtr image);
    [DllImport(CG)] private static extern nuint CGImageGetBytesPerRow(IntPtr image);
    [DllImport(CG)] private static extern nuint CGImageGetBitsPerPixel(IntPtr image);
    [DllImport(CG)] private static extern IntPtr CGImageGetDataProvider(IntPtr image);
    [DllImport(CG)] private static extern IntPtr CGDataProviderCopyData(IntPtr provider);
    [DllImport(CG)] private static extern void CGImageRelease(IntPtr image);
    [DllImport(CG)] [return: MarshalAs(UnmanagedType.I1)] private static extern bool CGRequestScreenCaptureAccess();
    [DllImport(CF)] private static extern IntPtr CFDataGetBytePtr(IntPtr data);
    [DllImport(CF)] private static extern void CFRelease(IntPtr obj);

    public MacScreenCapture()
    {
        try { CGRequestScreenCaptureAccess(); } catch { /* macOS < 10.15 */ }
    }

    public (int X, int Y) CursorPosition()
    {
        var ev = CGEventCreate(IntPtr.Zero);
        var p = CGEventGetLocation(ev);
        CFRelease(ev);
        return ((int)p.X, (int)p.Y);
    }

    public ScreenShot? Capture()
    {
        var (x, y) = CursorPosition();
        if (CGGetDisplaysWithPoint(new CGPoint { X = x, Y = y }, 1, out uint display, out uint count) != 0 || count == 0)
            display = CGMainDisplayID();
        var b = CGDisplayBounds(display); // points; the image is in pixels
        IntPtr image = CGDisplayCreateImage(display);
        if (image == IntPtr.Zero) return null;
        if (CGImageGetBitsPerPixel(image) != 32)
        {
            CGImageRelease(image);
            return null;
        }
        IntPtr data = CGDataProviderCopyData(CGImageGetDataProvider(image));
        if (data == IntPtr.Zero)
        {
            CGImageRelease(image);
            return null;
        }
        return new ScreenShot(
            CFDataGetBytePtr(data),
            (int)CGImageGetWidth(image),
            (int)CGImageGetHeight(image),
            (int)CGImageGetBytesPerRow(image),
            new ScreenRect((int)b.X, (int)b.Y, (int)b.W, (int)b.H),
            () =>
            {
                CFRelease(data);
                CGImageRelease(image);
            });
    }

    public void Dispose() { }
}
