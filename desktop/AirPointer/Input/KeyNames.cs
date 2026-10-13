namespace AirPointer.Input;

/// <summary>
/// Plays key combos sent by the phone's shortcut buttons.
/// "+" = keys pressed together ("Ctrl+Shift+Esc"), "," = one combo after another ("Ctrl+C, Alt+Tab, Ctrl+V").
/// Names are the canonical ones produced by the phone (android/.../KeyCombo.kt).
/// </summary>
public static class KeyNames
{
    // Real keyboards hold keys for tens of milliseconds. Many programs' hotkey detection
    // (and games/apps that poll key state) miss keys pressed and released in the same instant.
    private const int ModifierGapMs = 15;
    private const int HoldMs = 40;
    private const int StepGapMs = 60;

    // Auto-repeat while a shortcut button is held (Windows' default keyboard repeat).
    private const int RepeatDelayMs = 500;
    private const int RepeatIntervalMs = 33;

    private static readonly Dictionary<string, Key> Modifiers = new()
    {
        ["Ctrl"] = Key.Ctrl, ["Shift"] = Key.Shift, ["Alt"] = Key.Alt, ["Win"] = Key.Meta,
    };

    private static readonly Dictionary<string, Key> Named = new()
    {
        ["Enter"] = Key.Enter, ["Esc"] = Key.Escape, ["Tab"] = Key.Tab, ["Space"] = Key.Space,
        ["Backspace"] = Key.Backspace, ["Delete"] = Key.Delete, ["Insert"] = Key.Insert,
        ["Left"] = Key.Left, ["Right"] = Key.Right, ["Up"] = Key.Up, ["Down"] = Key.Down,
        ["Home"] = Key.Home, ["End"] = Key.End, ["PageUp"] = Key.PageUp, ["PageDown"] = Key.PageDown,
        ["PrintScreen"] = Key.PrintScreen, ["CapsLock"] = Key.CapsLock, ["NumLock"] = Key.NumLock,
        ["ScrollLock"] = Key.ScrollLock, ["Pause"] = Key.Pause, ["Menu"] = Key.Menu,
        ["VolumeUp"] = Key.VolumeUp, ["VolumeDown"] = Key.VolumeDown, ["Mute"] = Key.Mute,
        ["PlayPause"] = Key.PlayPause, ["NextTrack"] = Key.NextTrack, ["PrevTrack"] = Key.PrevTrack,
        ["MediaStop"] = Key.MediaStop,
    };

    private static readonly Dictionary<string, char> Symbols = new()
    {
        ["Minus"] = '-', ["Equals"] = '=', ["LeftBracket"] = '[', ["RightBracket"] = ']', ["Backslash"] = '\\',
        ["Semicolon"] = ';', ["Quote"] = '\'', ["Backtick"] = '`', ["Comma"] = ',', ["Period"] = '.', ["Slash"] = '/',
    };

    /// <summary>Taps a combo or a comma-separated sequence of combos.</summary>
    public static void RunCombo(IInputInjector input, string combo, Action<string> log)
    {
        log($"Shortcut: {combo}");
        var steps = combo.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        for (int i = 0; i < steps.Length; i++)
        {
            if (i > 0) Thread.Sleep(StepGapMs);
            RunStep(input, steps[i], log);
        }
    }

    /// <summary>
    /// Presses a combo and keeps it down (with keyboard-style auto-repeat) until the returned handle is
    /// disposed, i.e. until the phone's shortcut button is released. Returns null if nothing stays held.
    /// </summary>
    public static IDisposable? Hold(IInputInjector input, string combo, Action<string> log)
    {
        log($"Shortcut (held): {combo}");
        var step = combo.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).FirstOrDefault();
        if (step == null) return null;

        if (step is "BrightnessUp" or "BrightnessDown")
            return new Repeater(() => SystemControls.TryRun(step, input, log), intervalMs: 300);
        if (!step.Contains('+') && SystemControls.TryRun(step, input, log)) return null; // one-shot system action

        if (!TryParse(input, step, log, out var mods, out var main)) return null;
        foreach (var m in mods)
        {
            input.Key(m, true);
            Thread.Sleep(ModifierGapMs);
        }
        main?.Invoke(true);
        return new HeldKeys(input, mods, main);
    }

    private static void RunStep(IInputInjector input, string combo, Action<string> log)
    {
        if (!combo.Contains('+') && SystemControls.TryRun(combo, input, log)) return;
        if (!TryParse(input, combo, log, out var mods, out var main)) return;

        foreach (var m in mods)
        {
            input.Key(m, true);
            Thread.Sleep(ModifierGapMs);
        }
        if (main != null)
        {
            main(true);
            Thread.Sleep(HoldMs);
            main(false);
            Thread.Sleep(ModifierGapMs);
        }
        else
        {
            Thread.Sleep(HoldMs); // modifiers-only combo, e.g. "Alt+Win"
        }
        ReleaseModifiers(input, mods);
    }

    private static void ReleaseModifiers(IInputInjector input, List<Key> mods)
    {
        for (int i = mods.Count - 1; i >= 0; i--)
        {
            input.Key(mods[i], false);
            if (i > 0) Thread.Sleep(ModifierGapMs);
        }
    }

    private static bool TryParse(IInputInjector input, string combo, Action<string> log, out List<Key> mods, out Action<bool>? main)
    {
        mods = [];
        main = null;
        var parts = combo.Split('+', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length == 0) return false;
        foreach (var p in parts)
        {
            if (Modifiers.TryGetValue(p, out var m))
            {
                mods.Add(m);
                continue;
            }
            main = Resolve(p, input);
            if (main == null)
            {
                log($"Shortcut \"{combo}\": unknown key \"{p}\"");
                return false;
            }
        }
        return true;
    }

    private static Action<bool>? Resolve(string name, IInputInjector input)
    {
        if (name.Length == 1 && char.IsAsciiLetterOrDigit(name[0]))
        {
            char c = char.ToLowerInvariant(name[0]);
            return down => input.CharKey(c, down);
        }
        if (name.Length is 2 or 3 && name[0] == 'F' && int.TryParse(name.AsSpan(1), out int n) && n is >= 1 and <= 24)
            return down => input.FunctionKey(n, down);
        if (Symbols.TryGetValue(name, out char sym))
            return down => input.CharKey(sym, down);
        if (Named.TryGetValue(name, out var key))
            return down => input.Key(key, down);
        return null;
    }

    /// <summary>A held combo: repeats the main key like a keyboard does, releases everything on Dispose.</summary>
    private sealed class HeldKeys : IDisposable
    {
        private readonly IInputInjector _input;
        private readonly List<Key> _mods;
        private readonly Action<bool>? _main;
        private readonly CancellationTokenSource _stop = new();
        private readonly Task _repeat;
        private int _disposed;

        public HeldKeys(IInputInjector input, List<Key> mods, Action<bool>? main)
        {
            _input = input;
            _mods = mods;
            _main = main;
            _repeat = main == null ? Task.CompletedTask : Task.Run(RepeatLoop);
        }

        private async Task RepeatLoop()
        {
            try
            {
                await Task.Delay(RepeatDelayMs, _stop.Token);
                // Safety net: never auto-repeat for more than a minute even if the release got lost.
                var until = DateTime.UtcNow.AddMinutes(1);
                while (!_stop.IsCancellationRequested && DateTime.UtcNow < until)
                {
                    _main!(true); // repeated key-down = auto-repeat, exactly like a held physical key
                    await Task.Delay(RepeatIntervalMs, _stop.Token);
                }
            }
            catch (OperationCanceledException)
            {
                // released
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
            _stop.Cancel();
            try { _repeat.Wait(500); } catch { /* ignore */ }
            _main?.Invoke(false);
            if (_mods.Count > 0) Thread.Sleep(ModifierGapMs);
            ReleaseModifiers(_input, _mods);
        }
    }

    /// <summary>Runs an action now, then repeatedly while held (used for brightness).</summary>
    private sealed class Repeater : IDisposable
    {
        private readonly CancellationTokenSource _stop = new();

        public Repeater(Action action, int intervalMs)
        {
            action();
            Task.Run(async () =>
            {
                try
                {
                    await Task.Delay(RepeatDelayMs, _stop.Token);
                    while (!_stop.IsCancellationRequested)
                    {
                        action();
                        await Task.Delay(intervalMs, _stop.Token);
                    }
                }
                catch (OperationCanceledException)
                {
                    // released
                }
            });
        }

        public void Dispose() => _stop.Cancel();
    }
}
