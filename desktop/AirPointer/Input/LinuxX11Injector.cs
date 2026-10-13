using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using AirPointer.Core;

namespace AirPointer.Input;

/// <summary>
/// Linux implementation using the XTest extension (X11, or XWayland apps under Wayland).
/// Requires libX11 and libXtst (installed on virtually every desktop distro).
/// </summary>
[SupportedOSPlatform("linux")]
internal sealed class LinuxX11Injector : IInputInjector
{
    private const string X11 = "libX11.so.6";
    private const string Xtst = "libXtst.so.6";

    [DllImport(X11)] private static extern IntPtr XOpenDisplay(IntPtr name);
    [DllImport(X11)] private static extern int XCloseDisplay(IntPtr display);
    [DllImport(X11)] private static extern int XFlush(IntPtr display);
    [DllImport(X11)] private static extern byte XKeysymToKeycode(IntPtr display, nuint keysym);
    [DllImport(X11)] private static extern nuint XKeycodeToKeysym(IntPtr display, uint keycode, int index);

    [DllImport(Xtst)] private static extern int XTestFakeRelativeMotionEvent(IntPtr display, int dx, int dy, nuint delay);
    [DllImport(Xtst)] private static extern int XTestFakeButtonEvent(IntPtr display, uint button, int isPress, nuint delay);
    [DllImport(Xtst)] private static extern int XTestFakeKeyEvent(IntPtr display, uint keycode, int isPress, nuint delay);

    private readonly IntPtr _display;
    private int _scrollV, _scrollH; // remainder in 1/120 notch units

    public LinuxX11Injector()
    {
        _display = XOpenDisplay(IntPtr.Zero);
        if (_display == IntPtr.Zero)
            throw new InvalidOperationException(
                "Cannot open X display. Run inside an X11 session (or log in with \"GNOME on Xorg\" on Wayland systems).");
    }

    public void MoveRelative(int dx, int dy)
    {
        XTestFakeRelativeMotionEvent(_display, dx, dy, 0);
        XFlush(_display);
    }

    public void Button(MouseButton button, bool down)
    {
        uint b = button switch { MouseButton.Left => 1u, MouseButton.Middle => 2u, MouseButton.Right => 3u, _ => 0u };
        if (b == 0) return;
        XTestFakeButtonEvent(_display, b, down ? 1 : 0, 0);
        XFlush(_display);
    }

    public void Scroll(int vertical, int horizontal)
    {
        // X11 only knows whole wheel "clicks" (buttons 4/5 vertical, 6/7 horizontal).
        _scrollV += vertical;
        _scrollH += horizontal;
        while (_scrollV >= 120) { Click(4); _scrollV -= 120; }
        while (_scrollV <= -120) { Click(5); _scrollV += 120; }
        while (_scrollH >= 120) { Click(7); _scrollH -= 120; }
        while (_scrollH <= -120) { Click(6); _scrollH += 120; }
        XFlush(_display);
    }

    private void Click(uint button)
    {
        XTestFakeButtonEvent(_display, button, 1, 0);
        XTestFakeButtonEvent(_display, button, 0, 0);
    }

    public void Key(Key key, bool down)
    {
        nuint sym = KeySym(key);
        if (sym == 0) return;
        byte code = XKeysymToKeycode(_display, sym);
        if (code == 0) return;
        XTestFakeKeyEvent(_display, code, down ? 1 : 0, 0);
        XFlush(_display);
    }

    public void CharKey(char c, bool down) => PressSym(char.ToLowerInvariant(c), down);

    public void FunctionKey(int n, bool down)
    {
        if (n is >= 1 and <= 24) PressSym((nuint)(0xffbe + n - 1), down); // XK_F1..XK_F24
    }

    private void PressSym(nuint sym, bool down)
    {
        byte code = XKeysymToKeycode(_display, sym);
        if (code == 0) return;
        XTestFakeKeyEvent(_display, code, down ? 1 : 0, 0);
        XFlush(_display);
    }

    public void TypeText(string text)
    {
        byte shiftCode = XKeysymToKeycode(_display, 0xffe1);
        foreach (var rune in text.EnumerateRunes())
        {
            int cp = rune.Value;
            if (cp == '\r') continue;
            nuint sym = cp switch
            {
                '\n' => 0xff0d,
                '\t' => 0xff09,
                < 0x100 => (nuint)cp,
                _ => (nuint)(0x01000000 | cp),
            };
            byte code = XKeysymToKeycode(_display, sym);
            if (code == 0) continue; // character not on the current keyboard layout
            bool needShift = XKeycodeToKeysym(_display, code, 0) != sym && XKeycodeToKeysym(_display, code, 1) == sym;
            if (needShift) XTestFakeKeyEvent(_display, shiftCode, 1, 0);
            XTestFakeKeyEvent(_display, code, 1, 0);
            XTestFakeKeyEvent(_display, code, 0, 0);
            if (needShift) XTestFakeKeyEvent(_display, shiftCode, 0, 0);
        }
        XFlush(_display);
    }

    private static nuint KeySym(Key key) => key switch
    {
        Input.Key.Enter => 0xff0d,
        Input.Key.Backspace => 0xff08,
        Input.Key.Escape => 0xff1b,
        Input.Key.Tab => 0xff09,
        Input.Key.Space => 0x20,
        Input.Key.Delete => 0xffff,
        Input.Key.Left => 0xff51,
        Input.Key.Up => 0xff52,
        Input.Key.Right => 0xff53,
        Input.Key.Down => 0xff54,
        Input.Key.PageUp => 0xff55,
        Input.Key.PageDown => 0xff56,
        Input.Key.Home => 0xff50,
        Input.Key.End => 0xff57,
        Input.Key.VolumeUp => 0x1008FF13,
        Input.Key.VolumeDown => 0x1008FF11,
        Input.Key.Mute => 0x1008FF12,
        Input.Key.PlayPause => 0x1008FF14,
        Input.Key.NextTrack => 0x1008FF17,
        Input.Key.PrevTrack => 0x1008FF16,
        Input.Key.F5 => 0xffc2,
        Input.Key.F11 => 0xffc8,
        Input.Key.D => 0x64,
        Input.Key.Equals => 0x3d,
        Input.Key.Minus => 0x2d,
        Input.Key.Ctrl => 0xffe3,
        Input.Key.Shift => 0xffe1,
        Input.Key.Alt => 0xffe9,
        Input.Key.Meta => 0xffeb,
        Input.Key.Insert => 0xff63,
        Input.Key.PrintScreen => 0xff61,
        Input.Key.CapsLock => 0xffe5,
        Input.Key.NumLock => 0xff7f,
        Input.Key.ScrollLock => 0xff14,
        Input.Key.Pause => 0xff13,
        Input.Key.Menu => 0xff67,
        Input.Key.MediaStop => 0x1008FF15,
        Input.Key.BrightnessUp => 0x1008FF02,
        Input.Key.BrightnessDown => 0x1008FF03,
        _ => 0,
    };

    public void Dispose()
    {
        if (_display != IntPtr.Zero) XCloseDisplay(_display);
    }
}
