using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace AirPointer.Media;

/// <summary>
/// GDI capture of the monitor under the cursor, at its native resolution. The mouse pointer is not
/// part of the image; the phone draws its own at the reported cursor position (smoother than the frame rate).
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class WindowsScreenCapture : IScreenCapture
{
    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO { public int cbSize; public RECT rcMonitor, rcWork; public uint dwFlags; }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFOHEADER
    {
        public int biSize, biWidth, biHeight;
        public short biPlanes, biBitCount;
        public int biCompression, biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant;
    }

    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromPoint(POINT p, uint flags);
    [DllImport("user32.dll")] private static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hwnd, IntPtr dc);
    [DllImport("user32.dll")] private static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr dc, IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool GdiFlush();
    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateDIBSection(IntPtr dc, ref BITMAPINFOHEADER info, uint usage, out IntPtr bits, IntPtr section, uint offset);
    [DllImport("gdi32.dll")]
    private static extern bool BitBlt(IntPtr dst, int x, int y, int w, int h, IntPtr src, int sx, int sy, uint rop);
    [DllImport("winmm.dll")] private static extern uint timeBeginPeriod(uint ms);
    [DllImport("winmm.dll")] private static extern uint timeEndPeriod(uint ms);

    private const uint MONITOR_DEFAULTTONEAREST = 2;
    private const uint SRCCOPY = 0x00CC0020;
    private static readonly IntPtr PerMonitorAwareV2 = new(-4);

    private IntPtr _memDc, _bitmap, _oldBitmap, _bits;
    private int _width, _height;
    private POINT _lastCursor;

    static WindowsScreenCapture()
    {
        // Real pixels and cursor positions on scaled (high-DPI) monitors. Avalonia normally did this
        // already; it matters for --headless. Fails harmlessly if the awareness is already set.
        try { SetProcessDpiAwarenessContext(PerMonitorAwareV2); } catch { /* older Windows */ }
    }

    public WindowsScreenCapture()
    {
        timeBeginPeriod(1); // 1 ms sleeps, so high frame rates and cursor updates keep their pace
    }

    public (int X, int Y) CursorPosition()
    {
        if (GetCursorPos(out var p)) _lastCursor = p; // fails on the secure desktop: keep the last spot
        return (_lastCursor.X, _lastCursor.Y);
    }

    public ScreenShot? Capture()
    {
        var (cx, cy) = CursorPosition();
        var monitor = MonitorFromPoint(new POINT { X = cx, Y = cy }, MONITOR_DEFAULTTONEAREST);
        var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        if (monitor == IntPtr.Zero || !GetMonitorInfo(monitor, ref info)) return null;
        var r = info.rcMonitor;
        int w = r.Right - r.Left, h = r.Bottom - r.Top;
        if (w <= 0 || h <= 0) return null;

        IntPtr screen = GetDC(IntPtr.Zero);
        if (screen == IntPtr.Zero) return null;
        try
        {
            if (w != _width || h != _height) Allocate(screen, w, h);
            if (!BitBlt(_memDc, 0, 0, w, h, screen, r.Left, r.Top, SRCCOPY)) return null;
            GdiFlush();
        }
        finally
        {
            ReleaseDC(IntPtr.Zero, screen);
        }
        return new ScreenShot(_bits, w, h, w * 4, new ScreenRect(r.Left, r.Top, w, h));
    }

    private void Allocate(IntPtr screen, int w, int h)
    {
        FreeBitmap();
        _memDc = CreateCompatibleDC(screen);
        var header = new BITMAPINFOHEADER
        {
            biSize = Marshal.SizeOf<BITMAPINFOHEADER>(),
            biWidth = w,
            biHeight = -h, // top-down rows
            biPlanes = 1,
            biBitCount = 32,
        };
        _bitmap = CreateDIBSection(_memDc, ref header, 0, out _bits, IntPtr.Zero, 0);
        if (_bitmap == IntPtr.Zero) throw new InvalidOperationException("Could not allocate a screen buffer.");
        _oldBitmap = SelectObject(_memDc, _bitmap);
        _width = w;
        _height = h;
    }

    private void FreeBitmap()
    {
        if (_memDc != IntPtr.Zero)
        {
            SelectObject(_memDc, _oldBitmap);
            DeleteDC(_memDc);
        }
        if (_bitmap != IntPtr.Zero) DeleteObject(_bitmap);
        _memDc = _bitmap = _bits = IntPtr.Zero;
        _width = _height = 0;
    }

    public void Dispose()
    {
        FreeBitmap();
        timeEndPeriod(1);
    }
}
