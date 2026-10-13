using AirPointer.Core;
using Avalonia;

namespace AirPointer;

internal static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Contains("--headless")) return RunHeadless(args);
        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>().UsePlatformDetect().WithInterFont().LogToTrace();

    /// <summary>--openh264 on|off: screen view as H.264 on Linux/macOS with Cisco's codec (saved).</summary>
    private static void SetOpenH264(AppConfig config, bool on)
    {
        config.UseOpenH264 = on;
        config.Save();
        if (!on)
        {
            Console.WriteLine("  OpenH264 turned off: screen view sends JPEG images.");
            return;
        }
        Console.WriteLine($"  {Media.OpenH264.Attribution}");
        if (Media.OpenH264.IsDownloaded) return;
        Console.WriteLine("  Downloading it from Cisco (license: https://www.openh264.org/BINARY_LICENSE.txt)…");
        try
        {
            Media.OpenH264.DownloadAsync().GetAwaiter().GetResult();
            Console.WriteLine("  Done: screen view will use H.264.");
        }
        catch (Exception e)
        {
            Console.WriteLine($"  Download failed ({e.Message}); screen view keeps using JPEG.");
        }
    }

    /// <summary>Console-only mode for machines without a GUI tray (e.g. a Linux box you SSH into).</summary>
    /// <remarks>
    /// Options: --port N (this run only), --no-bluetooth, --pair (show a pairing QR code in the console),
    /// --openh264 on|off (H.264 screen view on Linux/macOS, downloads Cisco's OpenH264).
    /// </remarks>
    private static int RunHeadless(string[] args)
    {
        // The QR code's block characters (and "…") need UTF-8; the Windows console defaults to a legacy code page.
        try { Console.OutputEncoding = System.Text.Encoding.UTF8; } catch (IOException) { }
        var config = AppConfig.Load();
        int i = Array.IndexOf(args, "--port");
        if (i >= 0 && i + 1 < args.Length && int.TryParse(args[i + 1], out int port)) config.PortThisRun = port;
        if (args.Contains("--no-bluetooth")) config.NoBluetoothThisRun = true;
        int o = Array.IndexOf(args, "--openh264");
        if (o >= 0 && o + 1 < args.Length) SetOpenH264(config, args[o + 1] == "on");
        using var host = new ServerHost(config);
        host.Start();
        Console.WriteLine();
        foreach (var (addr, adapter) in ServerHost.LocalAddresses())
            Console.WriteLine($"  Listening on {addr}:{host.Config.ListenPort}   ({adapter})");
        Console.WriteLine($"  Paired phones: {host.PairedDevices.Count}");
        if (args.Contains("--pair"))
        {
            var w = host.StartPairing();
            using var data = new QRCoder.QRCodeGenerator().CreateQrCode(w.Link, QRCoder.QRCodeGenerator.ECCLevel.L);
            Console.WriteLine();
            // Light modules as blocks, so it reads correctly on the usual dark console background.
            Console.WriteLine(new QRCoder.AsciiQRCode(data).GetGraphic(1, "  ", "██"));
            Console.WriteLine($"  Scan with AirPointer on the phone (valid {ServerHost.PairingTimeout.TotalMinutes:0} minutes, one phone).");
            Console.WriteLine($"  Link: {w.Link}");
        }
        else
        {
            Console.WriteLine("  Run with --pair to pair a new phone.");
        }
        Console.WriteLine("  Press Ctrl+C to quit.");
        var quit = new ManualResetEventSlim();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; quit.Set(); };
        quit.Wait();
        return 0;
    }
}
