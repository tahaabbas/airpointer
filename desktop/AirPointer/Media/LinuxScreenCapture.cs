using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace AirPointer.Media;

/// <summary>X11 capture of the whole root window (all monitors) with XGetImage.</summary>
[SupportedOSPlatform("linux")]
internal sealed unsafe class LinuxScreenCapture : IScreenCapture
{
    private const string X11 = "libX11.so.6";
    private const int ZPixmap = 2;

    [DllImport(X11)] private static extern IntPtr XOpenDisplay(IntPtr name);
    [DllImport(X11)] private static extern int XCloseDisplay(IntPtr display);
    [DllImport(X11)] private static extern int XDefaultScreen(IntPtr display);
    [DllImport(X11)] private static extern IntPtr XDefaultRootWindow(IntPtr display);
    [DllImport(X11)] private static extern int XDisplayWidth(IntPtr display, int screen);
    [DllImport(X11)] private static extern int XDisplayHeight(IntPtr display, int screen);
    [DllImport(X11)]
    private static extern IntPtr XGetImage(IntPtr display, IntPtr drawable, int x, int y, uint w, uint h, nuint planeMask, int format);
    [DllImport(X11)]
    private static extern int XQueryPointer(IntPtr display, IntPtr window, out IntPtr root, out IntPtr child,
        out int rootX, out int rootY, out int winX, out int winY, out uint mask);

    // Leading fields of Xlib's XImage (64-bit layout).
    private const int OffWidth = 0, OffHeight = 4, OffData = 16, OffBytesPerLine = 44, OffBitsPerPixel = 48, OffDestroy = 96;

    private readonly IntPtr _display;
    private readonly IntPtr _root;
    private readonly int _screen;
    private (int, int) _lastCursor;

    public LinuxScreenCapture()
    {
        _display = XOpenDisplay(IntPtr.Zero);
        if (_display == IntPtr.Zero)
            throw new InvalidOperationException("Cannot open the X display (screen view needs an X11 session).");
        _screen = XDefaultScreen(_display);
        _root = XDefaultRootWindow(_display);
    }

    public (int X, int Y) CursorPosition()
    {
        if (XQueryPointer(_display, _root, out _, out _, out int x, out int y, out _, out _, out _) != 0)
            _lastCursor = (x, y);
        return _lastCursor;
    }

    public ScreenShot? Capture()
    {
        int w = XDisplayWidth(_display, _screen), h = XDisplayHeight(_display, _screen);
        IntPtr image = XGetImage(_display, _root, 0, 0, (uint)w, (uint)h, nuint.MaxValue, ZPixmap);
        if (image == IntPtr.Zero) return null;
        Action release = () => ((delegate* unmanaged[Cdecl]<IntPtr, int>)Marshal.ReadIntPtr(image, OffDestroy))(image); // XDestroyImage
        if (Marshal.ReadInt32(image, OffBitsPerPixel) != 32)
        {
            release();
            throw new NotSupportedException("Screen view needs a 24/32-bit X11 display.");
        }
        return new ScreenShot(
            Marshal.ReadIntPtr(image, OffData),
            Marshal.ReadInt32(image, OffWidth),
            Marshal.ReadInt32(image, OffHeight),
            Marshal.ReadInt32(image, OffBytesPerLine),
            new ScreenRect(0, 0, w, h),
            release);
    }

    public void Dispose() => XCloseDisplay(_display);
}
