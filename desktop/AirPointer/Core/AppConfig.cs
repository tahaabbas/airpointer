using System.Text.Json;
using System.Text.Json.Serialization;

namespace AirPointer.Core;

public sealed class AppConfig
{
    public int Port { get; set; } = Protocol.DefaultTcpPort;
    public bool EnableBluetooth { get; set; } = true;
    /// <summary>Lets connected phones see this screen (screen view).</summary>
    public bool AllowScreenView { get; set; } = true;
    /// <summary>Lets connected phones play this computer's sound. Configs from before it existed follow AllowScreenView.</summary>
    [JsonIgnore] public bool AllowSound { get => AllowSoundSaved ?? AllowScreenView; set => AllowSoundSaved = value; }
    [JsonPropertyName("AllowSound")] public bool? AllowSoundSaved { get; set; }
    /// <summary>Lets paired phones connect over the internet (iroh: direct when possible, else via a public relay).</summary>
    public bool AllowInternet { get; set; } = true;
    /// <summary>Linux/macOS: send screen view as H.264 with Cisco's OpenH264 (downloaded from Cisco when turned on).</summary>
    public bool UseOpenH264 { get; set; }
    /// <summary>Phones paired with the QR code; only these can connect.</summary>
    public List<PairedDevice> PairedDevices { get; set; } = [];

    /// <summary>Command-line overrides for this run only (--port, --no-bluetooth); never saved.</summary>
    [JsonIgnore] public int? PortThisRun { get; set; }
    [JsonIgnore] public bool NoBluetoothThisRun { get; set; }

    [JsonIgnore] public int ListenPort => PortThisRun ?? Port;
    [JsonIgnore] public bool BluetoothEnabled => EnableBluetooth && !NoBluetoothThisRun;

    /// <summary>%APPDATA%\AirPointer, or the folder in AIRPOINTER_HOME (portable use, testing).</summary>
    internal static string Dir =>
        Environment.GetEnvironmentVariable("AIRPOINTER_HOME") is { Length: > 0 } custom
            ? custom
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirPointer");

    private static string FilePath => Path.Combine(Dir, "config.json");

    private static readonly object SaveLock = new();

    public static AppConfig Load()
    {
        AppConfig? cfg = null;
        try
        {
            if (File.Exists(FilePath))
                cfg = JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(FilePath));
        }
        catch
        {
            // Corrupt config: fall back to defaults.
        }
        cfg ??= new AppConfig();
        if (cfg.Port is < 1 or > 65535) cfg.Port = Protocol.DefaultTcpPort;
        cfg.Save();
        return cfg;
    }

    public void Save()
    {
        try
        {
            lock (SaveLock)
            {
                Directory.CreateDirectory(Dir);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            }
        }
        catch
        {
            // Not fatal; settings just won't persist.
        }
    }
}

/// <summary>A phone that scanned this computer's pairing QR code.</summary>
public sealed class PairedDevice
{
    public string Name { get; set; } = "";
    /// <summary>Base64url SHA-256 of <see cref="PublicKey"/>; the phone sends it in every Hello.</summary>
    public string Id { get; set; } = "";
    /// <summary>Base64 SubjectPublicKeyInfo (ECDSA P-256) the phone signs with.</summary>
    public string PublicKey { get; set; } = "";
    public DateTime PairedAt { get; set; }
    public DateTime? LastSeen { get; set; }
}
