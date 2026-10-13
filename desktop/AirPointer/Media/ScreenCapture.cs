namespace AirPointer.Media;

/// <summary>A rectangle in the same coordinate space as <see cref="IScreenCapture.CursorPosition"/>.</summary>
public readonly record struct ScreenRect(int X, int Y, int Width, int Height)
{
    public bool Contains(int x, int y) => x >= X && y >= Y && x < X + Width && y < Y + Height;
}

/// <summary>
/// One captured screen image: 32-bit BGRA/BGRX pixels, valid until disposed.
/// <see cref="Rect"/> says where the image sits in cursor coordinates (the image can have more
/// pixels than the rect, e.g. on a Retina Mac, so the phone scales the cursor accordingly).
/// </summary>
public sealed class ScreenShot(IntPtr pixels, int width, int height, int stride, ScreenRect rect, Action? release = null) : IDisposable
{
    public IntPtr Pixels { get; } = pixels;
    public int Width { get; } = width;
    public int Height { get; } = height;
    public int Stride { get; } = stride;
    public ScreenRect Rect { get; } = rect;

    public unsafe ReadOnlySpan<byte> Bytes => new((void*)Pixels, Stride * Height);

    public void Dispose() => release?.Invoke();
}

/// <summary>Grabs the screen the cursor is on (Windows/macOS) or the whole X11 screen (Linux).</summary>
public interface IScreenCapture : IDisposable
{
    (int X, int Y) CursorPosition();

    /// <summary>Returns null when the screen can't be read right now (e.g. Windows lock screen / UAC prompt).</summary>
    ScreenShot? Capture();
}

public static class ScreenCapture
{
    public static IScreenCapture Create()
    {
        if (Environment.GetEnvironmentVariable("AIRPOINTER_TEST_SCREEN") is { Length: > 0 } test)
            return new TestScreenCapture(test, Environment.GetEnvironmentVariable("AIRPOINTER_TEST_SCREEN_MOTION") == "1");
        if (OperatingSystem.IsWindows()) return new WindowsScreenCapture();
        if (OperatingSystem.IsMacOS()) return new MacScreenCapture();
        if (OperatingSystem.IsLinux()) return new LinuxScreenCapture();
        throw new PlatformNotSupportedException("Screen view isn't supported on this operating system.");
    }
}
