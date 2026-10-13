using AirPointer.Core;

namespace AirPointer.Input;

/// <summary>OS-independent keys the injectors know how to press.</summary>
public enum Key
{
    Enter, Backspace, Escape, Tab, Space, Delete,
    Left, Right, Up, Down, PageUp, PageDown, Home, End,
    VolumeUp, VolumeDown, Mute, PlayPause, NextTrack, PrevTrack,
    F5, F11, D, Equals, Minus,
    Ctrl, Shift, Alt, Meta, // Meta = Windows key / Command / Super
    Insert, PrintScreen, CapsLock, NumLock, ScrollLock, Pause, Menu, MediaStop,
    BrightnessUp, BrightnessDown, // only real keys on Linux (XF86); Windows uses SystemControls
}

public interface IInputInjector : IDisposable
{
    /// <summary>Moves the cursor relative to its current position, like a physical mouse (crosses monitors).</summary>
    void MoveRelative(int dx, int dy);

    void Button(MouseButton button, bool down);

    /// <summary>Wheel units: 120 = one notch. Positive vertical = up, positive horizontal = right.</summary>
    void Scroll(int vertical, int horizontal);

    void Key(Key key, bool down);

    /// <summary>Presses the key that types <paramref name="c"/> (a-z, 0-9, symbols) on the current layout, without Shift.</summary>
    void CharKey(char c, bool down);

    /// <summary>F1..F24.</summary>
    void FunctionKey(int n, bool down);

    void TypeText(string text);
}

public static class InputInjector
{
    public static IInputInjector Create()
    {
        if (OperatingSystem.IsWindows()) return new WindowsInjector();
        if (OperatingSystem.IsMacOS()) return new MacInjector();
        if (OperatingSystem.IsLinux()) return new LinuxX11Injector();
        throw new PlatformNotSupportedException("Unsupported operating system");
    }
}

/// <summary>Serializes calls from multiple sessions; the native APIs are not all thread-safe.</summary>
public sealed class SyncInjector(IInputInjector inner) : IInputInjector
{
    private readonly object _lock = new();

    public void MoveRelative(int dx, int dy) { lock (_lock) inner.MoveRelative(dx, dy); }
    public void Button(MouseButton button, bool down) { lock (_lock) inner.Button(button, down); }
    public void Scroll(int vertical, int horizontal) { lock (_lock) inner.Scroll(vertical, horizontal); }
    public void Key(Key key, bool down) { lock (_lock) inner.Key(key, down); }
    public void CharKey(char c, bool down) { lock (_lock) inner.CharKey(c, down); }
    public void FunctionKey(int n, bool down) { lock (_lock) inner.FunctionKey(n, down); }
    public void TypeText(string text) { lock (_lock) inner.TypeText(text); }
    public void Dispose() { lock (_lock) inner.Dispose(); }
}

/// <summary>Maps phone-side keys/actions to per-OS key combos.</summary>
public static class Shortcuts
{
    public static void Combo(IInputInjector input, params Key[] keys)
    {
        foreach (var k in keys) input.Key(k, true);
        for (int i = keys.Length - 1; i >= 0; i--) input.Key(keys[i], false);
    }

    public static void PressRemoteKey(IInputInjector input, RemoteKey key)
    {
        Key? k = key switch
        {
            RemoteKey.Enter => Input.Key.Enter,
            RemoteKey.Backspace => Input.Key.Backspace,
            RemoteKey.Escape => Input.Key.Escape,
            RemoteKey.Tab => Input.Key.Tab,
            RemoteKey.Space => Input.Key.Space,
            RemoteKey.Delete => Input.Key.Delete,
            RemoteKey.Left => Input.Key.Left,
            RemoteKey.Right => Input.Key.Right,
            RemoteKey.Up => Input.Key.Up,
            RemoteKey.Down => Input.Key.Down,
            RemoteKey.PageUp => Input.Key.PageUp,
            RemoteKey.PageDown => Input.Key.PageDown,
            RemoteKey.Home => Input.Key.Home,
            RemoteKey.End => Input.Key.End,
            RemoteKey.VolumeUp => Input.Key.VolumeUp,
            RemoteKey.VolumeDown => Input.Key.VolumeDown,
            RemoteKey.Mute => Input.Key.Mute,
            RemoteKey.PlayPause => Input.Key.PlayPause,
            RemoteKey.NextTrack => Input.Key.NextTrack,
            RemoteKey.PrevTrack => Input.Key.PrevTrack,
            RemoteKey.F5 => Input.Key.F5,
            _ => null,
        };
        if (k is { } kk) Combo(input, kk);
    }

    public static void Run(IInputInjector input, RemoteAction action)
    {
        if (OperatingSystem.IsMacOS())
        {
            switch (action)
            {
                case RemoteAction.TaskView: Combo(input, Input.Key.Ctrl, Input.Key.Up); break; // Mission Control
                case RemoteAction.ShowDesktop: Combo(input, Input.Key.F11); break;
                case RemoteAction.NextApp: Combo(input, Input.Key.Meta, Input.Key.Tab); break;
                case RemoteAction.PrevApp: Combo(input, Input.Key.Meta, Input.Key.Shift, Input.Key.Tab); break;
            }
        }
        else
        {
            bool linux = OperatingSystem.IsLinux();
            switch (action)
            {
                case RemoteAction.TaskView:
                    if (linux) Combo(input, Input.Key.Meta); // GNOME/KDE overview
                    else Combo(input, Input.Key.Meta, Input.Key.Tab);
                    break;
                case RemoteAction.ShowDesktop: Combo(input, Input.Key.Meta, Input.Key.D); break;
                case RemoteAction.NextApp: Combo(input, Input.Key.Alt, Input.Key.Tab); break;
                case RemoteAction.PrevApp: Combo(input, Input.Key.Alt, Input.Key.Shift, Input.Key.Tab); break;
            }
        }
    }

    public static void Zoom(IInputInjector input, int steps)
    {
        if (steps == 0) return;
        if (OperatingSystem.IsMacOS())
        {
            for (int i = 0; i < Math.Abs(steps); i++)
                Combo(input, Input.Key.Meta, steps > 0 ? Input.Key.Equals : Input.Key.Minus);
            return;
        }
        input.Key(Input.Key.Ctrl, true);
        input.Scroll(120 * steps, 0);
        input.Key(Input.Key.Ctrl, false);
    }
}
