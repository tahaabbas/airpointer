using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using AirPointer.Core;

namespace AirPointer.Input;

/// <summary>
/// Uses SendInput with relative mouse movement, so the cursor behaves exactly like a physical
/// mouse: it moves across all monitors and respects the Windows pointer-speed settings.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class WindowsInjector : IInputInjector
{
    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;

    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x1000;

    private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion u;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKey(uint uCode, uint uMapType);

    private static void Send(params INPUT[] inputs)
    {
        if (inputs.Length == 0) return;
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
    }

    private static INPUT Mouse(uint flags, int dx = 0, int dy = 0, int data = 0) => new()
    {
        type = INPUT_MOUSE,
        u = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = unchecked((uint)data), dwFlags = flags } },
    };

    private static INPUT Keyboard(ushort vk, ushort scan, uint flags) => new()
    {
        type = INPUT_KEYBOARD,
        u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags } },
    };

    public void MoveRelative(int dx, int dy)
    {
        if (dx != 0 || dy != 0) Send(Mouse(MOUSEEVENTF_MOVE, dx, dy));
    }

    public void Button(MouseButton button, bool down)
    {
        uint flags = button switch
        {
            MouseButton.Left => down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP,
            MouseButton.Right => down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP,
            MouseButton.Middle => down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP,
            _ => 0,
        };
        if (flags != 0) Send(Mouse(flags));
    }

    public void Scroll(int vertical, int horizontal)
    {
        var list = new List<INPUT>(2);
        if (vertical != 0) list.Add(Mouse(MOUSEEVENTF_WHEEL, data: vertical));
        if (horizontal != 0) list.Add(Mouse(MOUSEEVENTF_HWHEEL, data: horizontal));
        Send(list.ToArray());
    }

    public void Key(Key key, bool down)
    {
        var (vk, extended) = VirtualKey(key);
        if (vk == 0) return;
        uint flags = (extended ? KEYEVENTF_EXTENDEDKEY : 0) | (down ? 0 : KEYEVENTF_KEYUP);
        // Pause/Break and NumLock share scan code 0x45 (NumLock is the "extended" one); MapVirtualKey can return 0 for Pause.
        ushort scan = vk == 0x13 ? (ushort)0x45 : (ushort)MapVirtualKey(vk, 0);
        Send(Keyboard(vk, scan, flags));
    }

    [DllImport("user32.dll")]
    private static extern short VkKeyScanW(char ch);

    [DllImport("user32.dll")] private static extern short VkKeyScanExW(char ch, IntPtr layout);
    [DllImport("user32.dll")] private static extern uint MapVirtualKeyExW(uint code, uint mapType, IntPtr layout);
    [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint threadId);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] private static extern short GetKeyState(int vk);

    /// <summary>Keyboard layout of the window that will receive the keys (apps can each use a different one).</summary>
    private static IntPtr ForegroundLayout()
    {
        uint thread = GetWindowThreadProcessId(GetForegroundWindow(), out _);
        return GetKeyboardLayout(thread);
    }

    public void CharKey(char c, bool down)
    {
        var layout = ForegroundLayout();
        short r = VkKeyScanExW(c, layout); // finds the key that types this character on that layout
        if (r == -1) return;
        ushort vk = (ushort)(r & 0xFF);
        Send(Keyboard(vk, (ushort)MapVirtualKeyExW(vk, 0, layout), down ? 0 : KEYEVENTF_KEYUP));
    }

    public void FunctionKey(int n, bool down)
    {
        if (n is < 1 or > 24) return;
        ushort vk = (ushort)(0x70 + n - 1); // VK_F1..VK_F24
        Send(Keyboard(vk, (ushort)MapVirtualKey(vk, 0), down ? 0 : KEYEVENTF_KEYUP));
    }

    /// <summary>
    /// Types text as real key presses (e.g. Shift+1 for "!") on the target window's keyboard layout, so it
    /// also passes through mouse/keyboard-sharing tools, remote desktop and games, which ignore "Unicode"
    /// input. Characters that aren't on the keyboard (emoji, etc.) fall back to Unicode input.
    /// </summary>
    public void TypeText(string text)
    {
        var layout = ForegroundLayout();
        bool capsLock = (GetKeyState(0x14) & 1) != 0;
        foreach (char c in text)
        {
            if (c == '\r') continue;
            var inputs = new List<INPUT>(8);
            ushort special = c switch { '\n' => 0x0D, '\t' => 0x09, _ => 0 };
            short r = special != 0 ? (short)special : VkKeyScanExW(c, layout);
            int shiftState = special != 0 ? 0 : (r >> 8) & 0xFF;
            int ctrlAlt = shiftState & 0x06;
            // Usable: plain or Shift, optionally AltGr (Ctrl+Alt). Anything exotic goes the Unicode route.
            bool usable = r != -1 && (r & 0xFF) != 0xFF && (shiftState & ~0x07) == 0 && (ctrlAlt == 0 || ctrlAlt == 0x06);

            if (usable)
            {
                ushort vk = (ushort)(r & 0xFF);
                bool shift = (shiftState & 1) != 0;
                bool altGr = (shiftState & 0x06) == 0x06; // Ctrl+Alt = AltGr on international layouts
                if (capsLock && char.IsLetter(c)) shift = !shift; // Caps Lock would otherwise flip the case
                var mods = new List<ushort>(3);
                if (shift) mods.Add(0x10);
                if (altGr) { mods.Add(0x11); mods.Add(0x12); }
                foreach (var m in mods) inputs.Add(Keyboard(m, (ushort)MapVirtualKeyExW(m, 0, layout), 0));
                ushort scan = (ushort)MapVirtualKeyExW(vk, 0, layout);
                inputs.Add(Keyboard(vk, scan, 0));
                inputs.Add(Keyboard(vk, scan, KEYEVENTF_KEYUP));
                for (int i = mods.Count - 1; i >= 0; i--)
                    inputs.Add(Keyboard(mods[i], (ushort)MapVirtualKeyExW(mods[i], 0, layout), KEYEVENTF_KEYUP));
            }
            else
            {
                inputs.Add(Keyboard(0, c, KEYEVENTF_UNICODE));
                inputs.Add(Keyboard(0, c, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
            }
            Send(inputs.ToArray()); // one character at a time keeps its modifiers together
        }
    }

    private static (ushort Vk, bool Extended) VirtualKey(Key key) => key switch
    {
        Input.Key.Enter => (0x0D, false),
        Input.Key.Backspace => (0x08, false),
        Input.Key.Escape => (0x1B, false),
        Input.Key.Tab => (0x09, false),
        Input.Key.Space => (0x20, false),
        Input.Key.Delete => (0x2E, true),
        Input.Key.Left => (0x25, true),
        Input.Key.Up => (0x26, true),
        Input.Key.Right => (0x27, true),
        Input.Key.Down => (0x28, true),
        Input.Key.PageUp => (0x21, true),
        Input.Key.PageDown => (0x22, true),
        Input.Key.End => (0x23, true),
        Input.Key.Home => (0x24, true),
        Input.Key.Mute => (0xAD, true),
        Input.Key.VolumeDown => (0xAE, true),
        Input.Key.VolumeUp => (0xAF, true),
        Input.Key.NextTrack => (0xB0, true),
        Input.Key.PrevTrack => (0xB1, true),
        Input.Key.PlayPause => (0xB3, true),
        Input.Key.F5 => (0x74, false),
        Input.Key.F11 => (0x7A, false),
        Input.Key.D => (0x44, false),
        Input.Key.Equals => (0xBB, false),
        Input.Key.Minus => (0xBD, false),
        Input.Key.Ctrl => (0x11, false),
        Input.Key.Shift => (0x10, false),
        Input.Key.Alt => (0x12, false),
        Input.Key.Meta => (0x5B, true),
        Input.Key.Insert => (0x2D, true),
        Input.Key.PrintScreen => (0x2C, true),
        Input.Key.CapsLock => (0x14, false),
        Input.Key.NumLock => (0x90, true),
        Input.Key.ScrollLock => (0x91, false),
        Input.Key.Pause => (0x13, false),
        Input.Key.Menu => (0x5D, true),
        Input.Key.MediaStop => (0xB2, true),
        _ => (0, false),
    };

    public void Dispose() { }
}
