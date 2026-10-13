using System.Diagnostics;
using System.Management;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
#if WINDOWS10_0_17763_0_OR_GREATER
using Windows.Devices.Radios;
#endif

namespace AirPointer.Input;

/// <summary>
/// System actions from the phone's shortcut buttons that aren't plain key presses:
/// brightness, Wi-Fi, airplane mode and lock. They run in the background so the
/// connection keeps processing input meanwhile.
/// </summary>
public static class SystemControls
{
    public static bool TryRun(string name, IInputInjector input, Action<string> log)
    {
        switch (name)
        {
            case "BrightnessUp":
            case "BrightnessDown":
                int delta = name == "BrightnessUp" ? 10 : -10;
                if (OperatingSystem.IsWindows()) Background(() => { if (OperatingSystem.IsWindows()) WindowsBrightness(delta, log); }, log);
                else if (OperatingSystem.IsLinux()) Shortcuts.Combo(input, delta > 0 ? Key.BrightnessUp : Key.BrightnessDown);
                else log("Brightness control isn't supported on macOS yet.");
                return true;

            case "WifiToggle":
            case "AirplaneToggle":
                bool wifiOnly = name == "WifiToggle";
                if (OperatingSystem.IsWindows())
                    Background(() => OperatingSystem.IsWindowsVersionAtLeast(10, 0, 10240) ? WindowsRadios(wifiOnly, log) : Task.CompletedTask, log);
                else if (OperatingSystem.IsLinux()) Background(() => LinuxRadios(wifiOnly, log), log);
                else log("Wi-Fi / airplane mode control isn't supported on macOS.");
                return true;

            case "Lock":
                if (OperatingSystem.IsWindows()) LockWorkStation();
                else if (OperatingSystem.IsLinux()) Background(() => Run("loginctl", "lock-session"), log);
                else Background(() => Run("pmset", "displaysleepnow"), log);
                return true;
        }
        return false;
    }

    private static void Background(Func<Task> work, Action<string> log) =>
        Task.Run(async () =>
        {
            try { await work(); }
            catch (Exception e) { log($"System control failed: {e.Message}"); }
        });

    private static void Background(Action work, Action<string> log) =>
        Background(() => { work(); return Task.CompletedTask; }, log);

    private static string Run(string file, string args)
    {
        var psi = new ProcessStartInfo(file, args) { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd();
        p.WaitForExit(5000);
        return output.Trim();
    }

    // ---------- Windows ----------

    [DllImport("user32.dll")]
    private static extern bool LockWorkStation();

    /// <summary>Laptop / built-in displays via WMI. External monitors normally don't support this.</summary>
    [SupportedOSPlatform("windows")]
    private static void WindowsBrightness(int delta, Action<string> log)
    {
        int? current = null;
        try
        {
            using var query = new ManagementObjectSearcher(@"root\WMI", "SELECT CurrentBrightness FROM WmiMonitorBrightness");
            foreach (ManagementObject o in query.Get())
            {
                current = Convert.ToInt32(o["CurrentBrightness"]);
                break;
            }
        }
        catch (ManagementException)
        {
            // "Not supported" on desktop monitors.
        }
        if (current == null)
        {
            log("Brightness: this display doesn't support software brightness control (usually only laptop screens do).");
            return;
        }
        byte target = (byte)Math.Clamp(current.Value + delta, 0, 100);
        using var methods = new ManagementObjectSearcher(@"root\WMI", "SELECT * FROM WmiMonitorBrightnessMethods");
        foreach (ManagementObject o in methods.Get())
            o.InvokeMethod("WmiSetBrightness", [(uint)1, target]);
    }

    [SupportedOSPlatform("windows10.0.10240")]
    private static async Task WindowsRadios(bool wifiOnly, Action<string> log)
    {
#if WINDOWS10_0_17763_0_OR_GREATER
        var access = await Radio.RequestAccessAsync();
        if (access != RadioAccessStatus.Allowed)
        {
            log($"Windows didn't allow radio control ({access}); opening Settings instead.");
            OpenSettings(wifiOnly);
            return;
        }
        var radios = await Radio.GetRadiosAsync();
        var targets = radios.Where(r => wifiOnly
            ? r.Kind == RadioKind.WiFi
            : r.Kind is RadioKind.WiFi or RadioKind.Bluetooth or RadioKind.MobileBroadband).ToList();
        if (targets.Count == 0)
        {
            log(wifiOnly ? "No Wi-Fi adapter found on this PC." : "No wireless radios found on this PC.");
            return;
        }
        // Toggle: if anything is on, turn everything off (airplane on); otherwise turn all back on.
        var newState = targets.Any(r => r.State == RadioState.On) ? RadioState.Off : RadioState.On;
        foreach (var r in targets)
        {
            var result = await r.SetStateAsync(newState);
            if (result != RadioAccessStatus.Allowed) log($"{r.Name}: {result}");
        }
        log(wifiOnly
            ? $"Wi-Fi turned {(newState == RadioState.On ? "on" : "off")}"
            : $"Airplane mode {(newState == RadioState.Off ? "on" : "off")}");
#else
        await Task.CompletedTask;
        OpenSettings(wifiOnly);
#endif
    }

    private static void OpenSettings(bool wifi) =>
        Process.Start(new ProcessStartInfo(wifi ? "ms-settings:network-wifi" : "ms-settings:network-airplanemode") { UseShellExecute = true });

    // ---------- Linux (NetworkManager) ----------

    private static void LinuxRadios(bool wifiOnly, Action<string> log)
    {
        string what = wifiOnly ? "wifi" : "all";
        // "nmcli radio wifi" prints enabled/disabled; "nmcli radio all" prints a table.
        bool anyOn = Run("nmcli", $"radio {what}").Contains("enabled");
        Run("nmcli", $"radio {what} {(anyOn ? "off" : "on")}");
        log(wifiOnly ? $"Wi-Fi turned {(anyOn ? "off" : "on")}" : $"Airplane mode {(anyOn ? "on" : "off")}");
    }
}
