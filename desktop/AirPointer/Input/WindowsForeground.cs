using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace AirPointer.Input;

/// <summary>
/// Windows silently drops input sent by a normal app into a window that runs as administrator (UIPI).
/// This detects that case so the log can explain why keys "do nothing".
/// </summary>
[SupportedOSPlatform("windows")]
internal static class WindowsForeground
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool GetTokenInformation(IntPtr token, int infoClass, out int info, int length, out int returned);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);

    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const uint TokenQuery = 0x0008;
    private const int TokenElevation = 20;
    private const int ErrorAccessDenied = 5;

    /// <summary>Name of the foreground app if it runs elevated while AirPointer doesn't; otherwise null.</summary>
    public static string? ElevatedForegroundApp()
    {
        if (Environment.IsPrivilegedProcess) return null;
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return null;
        GetWindowThreadProcessId(hwnd, out uint pid);
        var process = OpenProcess(ProcessQueryLimitedInformation, false, pid);
        if (process == IntPtr.Zero) return null;
        try
        {
            if (!OpenProcessToken(process, TokenQuery, out var token))
                return Marshal.GetLastWin32Error() == ErrorAccessDenied ? Name(pid) : null; // elevated tokens deny us
            try
            {
                return GetTokenInformation(token, TokenElevation, out int elevated, 4, out _) && elevated != 0 ? Name(pid) : null;
            }
            finally
            {
                CloseHandle(token);
            }
        }
        finally
        {
            CloseHandle(process);
        }
    }

    private static string Name(uint pid)
    {
        try { return Process.GetProcessById((int)pid).ProcessName; }
        catch { return "The active app"; }
    }
}
