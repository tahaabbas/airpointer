using System.Buffers.Text;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using AirPointer.Input;

namespace AirPointer.Core;

/// <summary>Owns the listeners (Wi-Fi, discovery, Bluetooth) and the list of connected phones.</summary>
public sealed class ServerHost : IDisposable
{
    private readonly List<Session> _sessions = [];
    private readonly List<MediaSession> _media = [];
    private readonly object _pairingLock = new();
    private PairingWindow? _pairing;
    private TcpServer? _tcp;
    private DiscoveryResponder? _discovery;
    private BluetoothRfcommServer? _bluetooth;
    private InternetServer? _internet;

    public AppConfig Config { get; }
    public Identity Identity { get; }
    public IInputInjector Injector { get; }
    public string WifiStatus { get; private set; } = "Not started";
    public string BluetoothStatus { get; private set; } = "Not started";
    public string InternetStatus { get; private set; } = "Not started";

    /// <summary>This computer's internet (iroh) id while internet access is on; phones learn it in Welcome.</summary>
    public byte[]? InternetId => _internet?.Id;

    public event Action<string>? LogMessage;
    public event Action? StateChanged;
    /// <summary>A phone finished pairing (any thread): the pairing window is closed.</summary>
    public event Action<PairedDevice>? Paired;

    public ServerHost(AppConfig config)
    {
        Config = config;
        Identity = Identity.LoadOrCreate();
        Injector = new SyncInjector(InputInjector.Create());
    }

    public void Start()
    {
        try
        {
            _tcp = new TcpServer(this, Config.ListenPort);
            _tcp.Start();
            WifiStatus = $"Listening on TCP port {Config.ListenPort}";
            Log(WifiStatus);
        }
        catch (Exception e)
        {
            WifiStatus = $"Could not listen on port {Config.ListenPort}: {e.Message}";
            Log(WifiStatus);
        }

        try
        {
            _discovery = new DiscoveryResponder(this);
            _discovery.Start();
        }
        catch (Exception e)
        {
            Log($"Auto-discovery unavailable (UDP {Protocol.DiscoveryPort}): {e.Message}. Enter the IP manually on the phone.");
        }

        if (!Config.BluetoothEnabled)
        {
            BluetoothStatus = "Disabled in settings";
        }
        else if (OperatingSystem.IsWindows())
        {
            try
            {
                _bluetooth = new BluetoothRfcommServer(this);
                int channel = _bluetooth.Start();
                BluetoothStatus = $"Ready (RFCOMM channel {channel}). Pair the phone in Windows Bluetooth settings first.";
            }
            catch (Exception e)
            {
                _bluetooth = null;
                BluetoothStatus = $"Unavailable: {e.Message}";
            }
            Log("Bluetooth: " + BluetoothStatus);
        }
        else
        {
            BluetoothStatus = "Use the phone's \"Bluetooth mouse (HID)\" mode on this OS - no app connection needed.";
        }

        StartInternet();
        StateChanged?.Invoke();
    }

    private void StartInternet()
    {
        if (!Config.AllowInternet)
        {
            InternetStatus = "Off";
            return;
        }
        try
        {
            var server = new InternetServer(this);
            server.Start(s =>
            {
                InternetStatus = s;
                Log("Internet: " + s);
                StateChanged?.Invoke();
            });
            _internet = server;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or InvalidOperationException or BadImageFormatException)
        {
            InternetStatus = e is DllNotFoundException ? "Not available in this build" : "Unavailable: " + e.Message;
            Log("Internet: " + InternetStatus);
        }
    }

    public void SetAllowInternet(bool allow)
    {
        Config.AllowInternet = allow;
        Config.Save();
        _internet?.Dispose();
        _internet = null;
        foreach (var s in Sessions.Where(s => s.Transport == "Internet")) s.Close();
        foreach (var m in MediaSessions.Where(m => m.Transport == "Internet")) m.Close();
        StartInternet();
        StateChanged?.Invoke();
    }

    public IReadOnlyList<Session> Sessions
    {
        get { lock (_sessions) return _sessions.ToArray(); }
    }

    internal void AddSession(Session s)
    {
        List<Session> stale;
        lock (_sessions)
        {
            // A phone that reconnects after a network hiccup replaces its old, half-dead session.
            stale = _sessions.Where(o => o.DeviceId == s.DeviceId).ToList();
            _sessions.Add(s);
        }
        foreach (var o in stale) o.Close();
        Log($"Connected: {s.DeviceName} via {s.Transport} ({s.RemoteKey})");
        StateChanged?.Invoke();
    }

    internal void RemoveSession(Session s)
    {
        lock (_sessions) _sessions.Remove(s);
        Log($"Disconnected: {s.DeviceName}");
        StateChanged?.Invoke();
    }

    public IReadOnlyList<MediaSession> MediaSessions
    {
        get { lock (_media) return _media.ToArray(); }
    }

    internal void AddMedia(MediaSession m)
    {
        lock (_media) _media.Add(m);
        Log($"{m.DeviceName}: {m.What} started");
        StateChanged?.Invoke();
    }

    internal void RemoveMedia(MediaSession m)
    {
        lock (_media) _media.Remove(m);
        Log($"{m.DeviceName}: {m.What} stopped");
        StateChanged?.Invoke();
    }

    public void SetAllowScreenView(bool allow)
    {
        Config.AllowScreenView = allow;
        Config.Save();
        if (!allow)
            foreach (var m in MediaSessions.Where(m => m.Kind == Protocol.MediaKindScreen)) m.Close();
        StateChanged?.Invoke();
    }

    public void SetAllowSound(bool allow)
    {
        Config.AllowSound = allow;
        Config.Save();
        if (!allow)
            foreach (var m in MediaSessions.Where(m => m.Kind == Protocol.MediaKindAudio)) m.Close();
        StateChanged?.Invoke();
    }

    // ---------- pairing ----------

    /// <summary>An open invitation to pair: what the QR code carries. Valid for a few minutes, for one phone.</summary>
    public sealed record PairingWindow(byte[] Token, DateTime Expires, string Link);

    public static readonly TimeSpan PairingTimeout = TimeSpan.FromMinutes(5);

    /// <summary>Opens (or renews) the pairing window and returns the link for the QR code.</summary>
    public PairingWindow StartPairing()
    {
        var token = RandomNumberGenerator.GetBytes(16);
        var hosts = LocalAddresses().Select(a => a.Address);
        var link = "airpointer://pair?v=" + Protocol.Version +
                   "&n=" + Uri.EscapeDataString(Protocol.ComputerName) +
                   "&h=" + string.Join(",", hosts) +
                   "&p=" + Config.ListenPort +
                   "&k=" + Identity.IdText +
                   "&t=" + Base64Url.EncodeToString(token);
        var w = new PairingWindow(token, DateTime.UtcNow + PairingTimeout, link);
        lock (_pairingLock) _pairing = w;
        return w;
    }

    public void CancelPairing()
    {
        lock (_pairingLock) _pairing = null;
    }

    /// <summary>A phone presented the QR code's token: remember it. Returns a Protocol status.</summary>
    internal byte CompletePairing(byte[] token, string name, byte[] publicKey)
    {
        lock (_pairingLock)
        {
            var w = _pairing;
            if (w == null || DateTime.UtcNow > w.Expires || !CryptographicOperations.FixedTimeEquals(token, w.Token))
                return Protocol.StatusPairingClosed;
            _pairing = null; // one phone per QR code
        }
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(publicKey, out _);
        }
        catch (CryptographicException)
        {
            return Protocol.StatusAuthFailed;
        }

        var id = Base64Url.EncodeToString(SHA256.HashData(publicKey));
        var device = new PairedDevice
        {
            Name = string.IsNullOrWhiteSpace(name) ? "Phone" : name,
            Id = id,
            PublicKey = Convert.ToBase64String(publicKey),
            PairedAt = DateTime.Now,
        };
        lock (Config.PairedDevices)
        {
            Config.PairedDevices.RemoveAll(d => d.Id == id); // the same phone pairing again
            Config.PairedDevices.Add(device);
        }
        Config.Save();
        Log($"Paired with {device.Name}");
        Paired?.Invoke(device);
        StateChanged?.Invoke();
        return Protocol.StatusOk;
    }

    internal PairedDevice? FindDevice(byte[] id)
    {
        if (id.Length != Auth.IdLength) return null;
        var text = Base64Url.EncodeToString(id);
        lock (Config.PairedDevices) return Config.PairedDevices.FirstOrDefault(d => d.Id == text);
    }

    internal void MarkSeen(PairedDevice d)
    {
        d.LastSeen = DateTime.Now;
        Config.Save();
    }

    public IReadOnlyList<PairedDevice> PairedDevices
    {
        get { lock (Config.PairedDevices) return Config.PairedDevices.ToArray(); }
    }

    /// <summary>Forgets a phone and drops its connections; it has to scan the QR code again to come back.</summary>
    public void RemoveDevice(string id)
    {
        PairedDevice? removed;
        lock (Config.PairedDevices)
        {
            removed = Config.PairedDevices.FirstOrDefault(d => d.Id == id);
            if (removed != null) Config.PairedDevices.Remove(removed);
        }
        if (removed == null) return;
        Config.Save();
        foreach (var s in Sessions.Where(s => s.DeviceId == id)) s.Close();
        foreach (var m in MediaSessions.Where(m => m.DeviceId == id)) m.Close();
        Log($"Removed {removed.Name}");
        StateChanged?.Invoke();
    }

    private static readonly object LogFileLock = new();

    /// <summary>%APPDATA%\AirPointer\airpointer.log (kept under ~1 MB) - useful for troubleshooting.</summary>
    public static string LogFilePath => Path.Combine(AppConfig.Dir, "airpointer.log");

    public void Log(string message)
    {
        var line = $"[{DateTime.Now:HH:mm:ss}] {message}";
        Console.WriteLine(line);
        LogMessage?.Invoke(line);
        try
        {
            lock (LogFileLock)
            {
                var file = new FileInfo(LogFilePath);
                if (file.Exists && file.Length > 1_000_000) File.Move(LogFilePath, LogFilePath + ".old", overwrite: true);
                File.AppendAllText(LogFilePath, $"{DateTime.Now:yyyy-MM-dd} {line}{Environment.NewLine}");
            }
        }
        catch
        {
            // Logging must never break input handling.
        }
    }

    /// <summary>IPv4 addresses the phone can use, with the adapter name; real Wi-Fi/Ethernet first, virtual adapters last.</summary>
    public static List<(string Address, string Adapter)> LocalAddresses()
    {
        // AIRPOINTER_DEMO=1 (screenshots): a sample address instead of this computer's real ones, in the
        // window and in the pairing QR code.
        if (Environment.GetEnvironmentVariable("AIRPOINTER_DEMO") == "1") return [("192.168.1.20", "Wi-Fi")];
        static bool Virtual(NetworkInterface ni) =>
            new[] { "vEthernet", "VirtualBox", "VMware", "Hyper-V", "WSL", "Docker", "Bluetooth", "Loopback", "TAP", "Tailscale", "ZeroTier" }
                .Any(k => ni.Name.Contains(k, StringComparison.OrdinalIgnoreCase) || ni.Description.Contains(k, StringComparison.OrdinalIgnoreCase));

        var list = new List<(string, string)>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                if (IPAddress.IsLoopback(ua.Address)) continue;
                if (ua.Address.ToString().StartsWith("169.254.")) continue;
                list.Add((ua.Address.ToString(), ni.Name));
            }
        }
        var virtualNames = NetworkInterface.GetAllNetworkInterfaces().Where(Virtual).Select(n => n.Name).ToHashSet();
        return list.OrderBy(a => virtualNames.Contains(a.Item2) ? 1 : 0).ToList();
    }

    public void Dispose()
    {
        _tcp?.Dispose();
        _discovery?.Dispose();
        _internet?.Dispose();
        if (OperatingSystem.IsWindows()) _bluetooth?.Dispose();
        foreach (var s in Sessions) s.Close();
        foreach (var m in MediaSessions) m.Close();
        Identity.Dispose();
    }
}
