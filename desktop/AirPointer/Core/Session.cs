using System.Net.Sockets;
using AirPointer.Input;

namespace AirPointer.Core;

/// <summary>One connected phone (over Wi-Fi or Bluetooth). Runs on its own thread.</summary>
public sealed class Session
{
    private readonly Stream _stream;
    private readonly ServerHost _host;
    private readonly HashSet<MouseButton> _held = [];
    private bool _loggedInjectError;
    private readonly HashSet<byte> _unknownTypes = [];
    private readonly HashSet<string> _warnedElevated = [];
    private IDisposable? _heldCombo; // shortcut button currently held on the phone

    public string Transport { get; }
    public string RemoteKey { get; }
    public string DeviceName { get; private set; } = "?";
    /// <summary>The paired phone's id (see <see cref="PairedDevice.Id"/>).</summary>
    public string DeviceId { get; private set; } = "";
    public DateTime ConnectedAt { get; } = DateTime.Now;

    public Session(Stream stream, string transport, string remoteKey, ServerHost host)
    {
        _stream = stream;
        Transport = transport;
        RemoteKey = remoteKey;
        _host = host;
    }

    private IInputInjector Input => _host.Injector;

    public void Run()
    {
        var reader = new FrameReader(_stream);
        bool added = false;
        try
        {
            var first = reader.Read();
            switch (first?.Type)
            {
                case Protocol.PairRequest:
                    Pair(first.Value.Payload);
                    return;
                case Protocol.MediaHello:
                    new MediaSession(_stream, reader, Transport, _host).Run(first.Value.Payload);
                    return;
                case Protocol.Hello:
                    break;
                default:
                    return;
            }

            var p = new PayloadReader(first.Value.Payload);
            if (ReadHello(p, _stream, _host, Transport) is not { } hello) return;
            if (Auth.Authenticate(_stream, reader, _host, hello.Id, hello.Nonce, hello.Name, Transport) is not { } device) return;
            DeviceName = device.Name;
            DeviceId = device.Id;

            SendWelcome(Protocol.StatusOk);
            _host.AddSession(this);
            added = true;

            while (reader.Read() is { } frame)
                Dispatch(frame);
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException
                                      or IndexOutOfRangeException or ArgumentException)
        {
            // Connection dropped or malformed frame: just end the session.
        }
        finally
        {
            try { _heldCombo?.Dispose(); } catch { /* ignore */ }
            _heldCombo = null;
            ReleaseHeldButtons();
            if (added) _host.RemoveSession(this);
            try { _stream.Dispose(); } catch { /* ignore */ }
        }
    }

    public void Close()
    {
        try { _stream.Dispose(); } catch { /* ignore */ }
    }

    internal readonly record struct HelloFields(string Name, byte[] Id, byte[] Nonce);

    /// <summary>Reads the Hello/MediaHello fields shared by both; refuses other protocol versions.</summary>
    internal static HelloFields? ReadHello(PayloadReader p, Stream stream, ServerHost host, string transport)
    {
        int version = p.U8();
        if (version < Protocol.MinVersion || version > Protocol.Version)
        {
            // Older phones only know the PIN: they show "versions don't match - update both apps".
            WriteWelcome(stream, Protocol.StatusBadVersion);
            host.Log($"Refused a phone ({transport}): app version {version} is too old; update AirPointer on the phone.");
            return null;
        }
        string name = p.Str8();
        var id = p.Bytes(Auth.IdLength);
        var nonce = p.Bytes(Auth.NonceLength);
        return new HelloFields(name, id, nonce);
    }

    /// <summary>A phone that scanned the pairing QR code: check its one-time token and remember its key.</summary>
    private void Pair(byte[] payload)
    {
        var p = new PayloadReader(payload);
        int version = p.U8();
        byte status;
        if (version < Protocol.MinVersion || version > Protocol.Version) status = Protocol.StatusBadVersion;
        else if (Transport != "Wi-Fi") status = Protocol.StatusUnavailable; // the QR token must travel inside TLS
        else
        {
            var token = p.Bytes8();
            var name = p.Str8();
            var publicKey = p.Bytes16();
            status = _host.CompletePairing(token, name, publicKey);
            if (status != Protocol.StatusOk) _host.Log($"Pairing refused for {name}: the QR code expired or was already used.");
        }
        FrameWriter.Write(_stream, Protocol.PairResult,
        [
            status, .. FrameWriter.Str8(Protocol.ComputerName), .. FrameWriter.Str8(Protocol.OsName),
            .. FrameWriter.Bytes16(_host.Identity.PublicKey),
        ]);
    }

    private void SendWelcome(byte status) => WriteWelcome(_stream, status, internetId: _host.InternetId);

    /// <summary>
    /// Welcome = status, str8 name, str8 os, then str8 reason (refusals) or bytes8 internet id (OK, when
    /// internet access is on: the phone remembers it to reach this computer from anywhere).
    /// </summary>
    internal static void WriteWelcome(Stream stream, byte status, string? reason = null, byte[]? internetId = null)
    {
        var payload = new List<byte> { status };
        payload.AddRange(FrameWriter.Str8(Protocol.ComputerName));
        payload.AddRange(FrameWriter.Str8(Protocol.OsName));
        if (reason != null) payload.AddRange(FrameWriter.Str8(reason));
        else if (internetId != null && status == Protocol.StatusOk) payload.AddRange(FrameWriter.Bytes8(internetId));
        FrameWriter.Write(stream, Protocol.Welcome, payload.ToArray());
    }

    private void Dispatch(Frame f)
    {
        var p = new PayloadReader(f.Payload);
        try
        {
            switch (f.Type)
            {
                case Protocol.Ping:
                    FrameWriter.Write(_stream, Protocol.Pong, ReadOnlySpan<byte>.Empty);
                    break;

                case Protocol.Move:
                {
                    int dx = p.I16(), dy = p.I16();
                    Input.MoveRelative(dx, dy);
                    break;
                }

                case Protocol.Button:
                {
                    var b = (MouseButton)p.U8();
                    var a = (ButtonAction)p.U8();
                    if (!Enum.IsDefined(b)) break;
                    switch (a)
                    {
                        case ButtonAction.Down:
                            _held.Add(b);
                            Input.Button(b, true);
                            break;
                        case ButtonAction.Up:
                            _held.Remove(b);
                            Input.Button(b, false);
                            break;
                        case ButtonAction.Click:
                            Input.Button(b, true);
                            Input.Button(b, false);
                            break;
                    }
                    break;
                }

                case Protocol.Scroll:
                {
                    int v = p.I16(), h = p.I16();
                    Input.Scroll(v, h);
                    break;
                }

                case Protocol.Zoom:
                    Shortcuts.Zoom(Input, (sbyte)p.U8());
                    break;

                case Protocol.Key:
                    WarnIfBlocked();
                    Shortcuts.PressRemoteKey(Input, (RemoteKey)p.U8());
                    break;

                case Protocol.Text:
                    WarnIfBlocked();
                    Input.TypeText(p.Str16());
                    break;

                case Protocol.Action:
                    Shortcuts.Run(Input, (RemoteAction)p.U8());
                    break;

                case Protocol.Combo:
                    WarnIfBlocked();
                    KeyNames.RunCombo(Input, p.Str8(), _host.Log);
                    break;

                case Protocol.ComboDown:
                    WarnIfBlocked();
                    _heldCombo?.Dispose();
                    _heldCombo = KeyNames.Hold(Input, p.Str8(), _host.Log);
                    break;

                case Protocol.ComboUp:
                    _heldCombo?.Dispose();
                    _heldCombo = null;
                    break;

                default:
                    if (_unknownTypes.Add(f.Type))
                        _host.Log($"{DeviceName} sent an unknown command (0x{f.Type:X2}) - is the phone app newer than this PC app?");
                    break;
            }
        }
        catch (Exception e) when (e is not (IOException or SocketException or ObjectDisposedException
                                              or IndexOutOfRangeException or ArgumentException))
        {
            // A failing OS call must not kill the connection; report it once.
            if (!_loggedInjectError)
            {
                _loggedInjectError = true;
                _host.Log($"Input injection failed: {e.Message}");
            }
        }
    }

    /// <summary>Explains (once per app) when Windows will silently block our keystrokes.</summary>
    private void WarnIfBlocked()
    {
        if (!OperatingSystem.IsWindows()) return;
        var app = WindowsForeground.ElevatedForegroundApp();
        if (app != null && _warnedElevated.Add(app))
            _host.Log($"Note: \"{app}\" is running as administrator, so Windows blocks AirPointer's keys and clicks in it. " +
                      "Close AirPointer and start it with right-click → Run as administrator to control that app.");
    }

    private void ReleaseHeldButtons()
    {
        foreach (var b in _held)
        {
            try { Input.Button(b, false); } catch { /* ignore */ }
        }
        _held.Clear();
    }
}
