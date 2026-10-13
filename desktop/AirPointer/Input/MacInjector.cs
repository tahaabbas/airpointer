using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using AirPointer.Core;

namespace AirPointer.Input;

/// <summary>
/// macOS implementation using CoreGraphics events. The app must be allowed under
/// System Settings &gt; Privacy &amp; Security &gt; Accessibility, otherwise events are silently dropped.
/// </summary>
[SupportedOSPlatform("macos")]
internal sealed class MacInjector : IInputInjector
{
    private const string CG = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";
    private const string CF = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private const string AS = "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices";

    [StructLayout(LayoutKind.Sequential)]
    private struct CGPoint { public double X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct CGRect { public double X, Y, W, H; }

    [DllImport(CG)] private static extern IntPtr CGEventCreate(IntPtr source);
    [DllImport(CG)] private static extern CGPoint CGEventGetLocation(IntPtr ev);
    [DllImport(CG)] private static extern IntPtr CGEventCreateMouseEvent(IntPtr source, uint type, CGPoint pos, uint button);
    [DllImport(CG)] private static extern IntPtr CGEventCreateKeyboardEvent(IntPtr source, ushort keycode, [MarshalAs(UnmanagedType.I1)] bool down);
    [DllImport(CG)] private static extern IntPtr CGEventCreateScrollWheelEvent2(IntPtr source, uint units, uint wheelCount, int w1, int w2, int w3);
    [DllImport(CG)] private static extern void CGEventKeyboardSetUnicodeString(IntPtr ev, nuint length, char[] chars);
    [DllImport(CG)] private static extern void CGEventSetIntegerValueField(IntPtr ev, uint field, long value);
    [DllImport(CG)] private static extern void CGEventSetFlags(IntPtr ev, ulong flags);
    [DllImport(CG)] private static extern void CGEventPost(uint tap, IntPtr ev);
    [DllImport(CG)] private static extern int CGGetActiveDisplayList(uint max, [Out] uint[] displays, out uint count);
    [DllImport(CG)] private static extern CGRect CGDisplayBounds(uint display);
    [DllImport(CF)] private static extern void CFRelease(IntPtr obj);
    [DllImport(AS)] [return: MarshalAs(UnmanagedType.I1)] private static extern bool AXIsProcessTrusted();

    private const uint HidEventTap = 0;
    private const uint MouseEventClickState = 1;
    private const uint MouseEventDeltaX = 4;
    private const uint MouseEventDeltaY = 5;

    private const ulong FlagShift = 0x20000, FlagCtrl = 0x40000, FlagAlt = 0x80000, FlagCmd = 0x100000;

    private readonly bool[] _down = new bool[3]; // left, right, middle
    private ulong _modifiers;
    private DateTime _lastClickTime;
    private CGPoint _lastClickPos;
    private int _clickCount;

    public MacInjector()
    {
        try
        {
            if (!AXIsProcessTrusted())
                Console.WriteLine("WARNING: grant AirPointer Accessibility permission (System Settings > Privacy & Security > Accessibility).");
        }
        catch
        {
            // Older systems: ignore.
        }
    }

    private static CGPoint CurrentLocation()
    {
        var ev = CGEventCreate(IntPtr.Zero);
        var p = CGEventGetLocation(ev);
        CFRelease(ev);
        return p;
    }

    private static CGPoint ClampToDisplays(CGPoint p)
    {
        var ids = new uint[16];
        if (CGGetActiveDisplayList(16, ids, out uint count) != 0 || count == 0) return p;
        double best = double.MaxValue;
        CGPoint bestPoint = p;
        for (int i = 0; i < count; i++)
        {
            var r = CGDisplayBounds(ids[i]);
            double cx = Math.Clamp(p.X, r.X, r.X + r.W - 1);
            double cy = Math.Clamp(p.Y, r.Y, r.Y + r.H - 1);
            double d = (cx - p.X) * (cx - p.X) + (cy - p.Y) * (cy - p.Y);
            if (d < best)
            {
                best = d;
                bestPoint = new CGPoint { X = cx, Y = cy };
            }
        }
        return bestPoint;
    }

    private void Post(IntPtr ev)
    {
        if (ev == IntPtr.Zero) return;
        CGEventSetFlags(ev, _modifiers);
        CGEventPost(HidEventTap, ev);
        CFRelease(ev);
    }

    public void MoveRelative(int dx, int dy)
    {
        var cur = CurrentLocation();
        var target = ClampToDisplays(new CGPoint { X = cur.X + dx, Y = cur.Y + dy });
        uint type = _down[0] ? 6u : _down[1] ? 7u : _down[2] ? 27u : 5u; // dragged variants while a button is held
        uint button = _down[0] ? 0u : _down[1] ? 1u : _down[2] ? 2u : 0u;
        var ev = CGEventCreateMouseEvent(IntPtr.Zero, type, target, button);
        CGEventSetIntegerValueField(ev, MouseEventDeltaX, dx);
        CGEventSetIntegerValueField(ev, MouseEventDeltaY, dy);
        Post(ev);
    }

    public void Button(MouseButton button, bool down)
    {
        int idx = button switch { MouseButton.Left => 0, MouseButton.Right => 1, MouseButton.Middle => 2, _ => -1 };
        if (idx < 0) return;
        uint type = (idx, down) switch
        {
            (0, true) => 1, (0, false) => 2,
            (1, true) => 3, (1, false) => 4,
            (_, true) => 25, (_, false) => 26,
        };
        var pos = CurrentLocation();
        if (down)
        {
            // macOS needs an explicit click count for double/triple clicks.
            var now = DateTime.UtcNow;
            bool near = Math.Abs(pos.X - _lastClickPos.X) < 5 && Math.Abs(pos.Y - _lastClickPos.Y) < 5;
            _clickCount = (now - _lastClickTime).TotalMilliseconds < 500 && near ? Math.Min(_clickCount + 1, 3) : 1;
            _lastClickTime = now;
            _lastClickPos = pos;
        }
        _down[idx] = down;
        var ev = CGEventCreateMouseEvent(IntPtr.Zero, type, pos, (uint)idx);
        CGEventSetIntegerValueField(ev, MouseEventClickState, Math.Max(_clickCount, 1));
        Post(ev);
    }

    public void Scroll(int vertical, int horizontal)
    {
        // Pixel units give smooth scrolling; 120 units (one notch) ~ 40 px.
        var ev = CGEventCreateScrollWheelEvent2(IntPtr.Zero, 0, 2, vertical / 3, -horizontal / 3, 0);
        Post(ev);
    }

    public void Key(Key key, bool down)
    {
        ulong flag = key switch
        {
            Input.Key.Shift => FlagShift,
            Input.Key.Ctrl => FlagCtrl,
            Input.Key.Alt => FlagAlt,
            Input.Key.Meta => FlagCmd,
            _ => 0,
        };
        if (flag != 0) _modifiers = down ? _modifiers | flag : _modifiers & ~flag;

        ushort code = KeyCode(key);
        if (code == 0xFFFF) return; // e.g. media keys are not regular key codes on macOS
        Post(CGEventCreateKeyboardEvent(IntPtr.Zero, code, down));
    }

    // US ANSI virtual key codes for the character keys.
    private static readonly Dictionary<char, ushort> CharCodes = new()
    {
        ['a'] = 0, ['s'] = 1, ['d'] = 2, ['f'] = 3, ['h'] = 4, ['g'] = 5, ['z'] = 6, ['x'] = 7, ['c'] = 8, ['v'] = 9,
        ['b'] = 11, ['q'] = 12, ['w'] = 13, ['e'] = 14, ['r'] = 15, ['y'] = 16, ['t'] = 17, ['1'] = 18, ['2'] = 19,
        ['3'] = 20, ['4'] = 21, ['6'] = 22, ['5'] = 23, ['='] = 24, ['9'] = 25, ['7'] = 26, ['-'] = 27, ['8'] = 28,
        ['0'] = 29, [']'] = 30, ['o'] = 31, ['u'] = 32, ['['] = 33, ['i'] = 34, ['p'] = 35, ['l'] = 37, ['j'] = 38,
        ['\''] = 39, ['k'] = 40, [';'] = 41, ['\\'] = 42, [','] = 43, ['/'] = 44, ['n'] = 45, ['m'] = 46, ['.'] = 47,
        ['`'] = 50,
    };

    private static readonly ushort[] FKeyCodes = [122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111, 105, 107, 113, 106, 64, 79, 80, 90];

    public void CharKey(char c, bool down)
    {
        if (CharCodes.TryGetValue(char.ToLowerInvariant(c), out var code))
            Post(CGEventCreateKeyboardEvent(IntPtr.Zero, code, down));
    }

    public void FunctionKey(int n, bool down)
    {
        if (n >= 1 && n <= FKeyCodes.Length) Post(CGEventCreateKeyboardEvent(IntPtr.Zero, FKeyCodes[n - 1], down));
    }

    public void TypeText(string text)
    {
        foreach (char c in text)
        {
            if (c == '\r') continue;
            if (c == '\n') { Key(Input.Key.Enter, true); Key(Input.Key.Enter, false); continue; }
            foreach (bool down in new[] { true, false })
            {
                var ev = CGEventCreateKeyboardEvent(IntPtr.Zero, 0, down);
                CGEventKeyboardSetUnicodeString(ev, 1, [c]);
                Post(ev);
            }
        }
    }

    private static ushort KeyCode(Key key) => key switch
    {
        Input.Key.Enter => 36,
        Input.Key.Tab => 48,
        Input.Key.Space => 49,
        Input.Key.Backspace => 51,
        Input.Key.Escape => 53,
        Input.Key.Meta => 55,
        Input.Key.Shift => 56,
        Input.Key.Alt => 58,
        Input.Key.Ctrl => 59,
        Input.Key.F5 => 96,
        Input.Key.F11 => 103,
        Input.Key.Home => 115,
        Input.Key.PageUp => 116,
        Input.Key.Delete => 117,
        Input.Key.End => 119,
        Input.Key.PageDown => 121,
        Input.Key.Left => 123,
        Input.Key.Right => 124,
        Input.Key.Down => 125,
        Input.Key.Up => 126,
        Input.Key.D => 2,
        Input.Key.Equals => 24,
        Input.Key.Minus => 27,
        Input.Key.Insert => 114, // "Help" key position on Mac keyboards
        Input.Key.CapsLock => 57,
        _ => 0xFFFF,
    };

    public void Dispose() { }
}
