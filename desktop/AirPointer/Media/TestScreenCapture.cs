using System.Diagnostics;
using SkiaSharp;

namespace AirPointer.Media;

/// <summary>
/// Testing aid: "captures" an image file instead of the real screen, so screen view can be tried without
/// showing what's on this computer. Set AIRPOINTER_TEST_SCREEN=path\to\image.png (and
/// AIRPOINTER_TEST_SCREEN_MOTION=1 to scroll it sideways, for motion). The cursor circles around the middle.
/// </summary>
internal sealed class TestScreenCapture : IScreenCapture
{
    private readonly byte[] _image;
    private readonly byte[] _frame;
    private readonly int _width, _height;
    private readonly bool _motion;
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    public TestScreenCapture(string path, bool motion)
    {
        using var decoded = SKBitmap.Decode(path) ?? throw new InvalidOperationException($"Can't read test screen {path}");
        using var bgra = decoded.Copy(SKColorType.Bgra8888);
        _width = bgra.Width;
        _height = bgra.Height;
        _image = bgra.Bytes;
        _frame = GC.AllocateArray<byte>(_image.Length, pinned: true); // handed out as a pointer below
        _motion = motion;
    }

    public (int X, int Y) CursorPosition()
    {
        double t = _clock.Elapsed.TotalSeconds * 0.8;
        return ((int)(_width / 2 + _width * 0.3 * Math.Cos(t)), (int)(_height / 2 + _height * 0.3 * Math.Sin(t)));
    }

    public ScreenShot? Capture()
    {
        int stride = _width * 4;
        int shift = _motion ? (int)(_clock.ElapsedMilliseconds / 8 % _width) * 4 : 0;
        for (int y = 0; y < _height; y++)
        {
            var row = _image.AsSpan(y * stride, stride);
            var dst = _frame.AsSpan(y * stride, stride);
            row[shift..].CopyTo(dst);
            row[..shift].CopyTo(dst[(stride - shift)..]);
        }
        return new ScreenShot(System.Runtime.InteropServices.Marshal.UnsafeAddrOfPinnedArrayElement(_frame, 0),
            _width, _height, stride, new ScreenRect(0, 0, _width, _height));
    }

    public void Dispose() { }
}
