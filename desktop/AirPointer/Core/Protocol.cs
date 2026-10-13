using System.Buffers.Binary;
using System.Text;

namespace AirPointer.Core;

/// <summary>
/// Wire protocol shared with the Android app (see Protocol.kt).
/// Every frame is: [u16 length, big-endian][u8 type][payload], where length = 1 + payload length.
/// </summary>
public static class Protocol
{
    /// <summary>
    /// 2 = adds Combo (shortcut buttons). 3 = adds ComboDown/ComboUp (hold to auto-repeat).
    /// 4 = adds media connections (screen view + computer sound, see MediaHello).
    /// 5 = QR pairing and per-device keys instead of the PIN; Wi-Fi connections use TLS.
    /// Older phones are refused with "versions don't match" (they only know the PIN).
    /// </summary>
    public const int Version = 5;
    public const int MinVersion = 5;
    public const int DefaultTcpPort = 50505;
    public const int DiscoveryPort = 50506;
    public const string DiscoveryRequest = "AIRPTR?";
    public const string DiscoveryReplyPrefix = "AIRPTR!";
    public static readonly Guid BluetoothServiceId = new("7a1d3b2e-5c4f-4e8a-9b6d-2f0e1c3a4b5d");

    // Handshake / keep-alive. Wi-Fi connections are TLS first (the PC's certificate is pinned by the phone);
    // Bluetooth relies on the Bluetooth link's own encryption. Either way both sides then prove who they are:
    //   c->s Hello (or MediaHello) → s->c AuthChallenge → c->s AuthResponse → s->c Welcome.
    // An unpaired phone gets Welcome(StatusNotPaired) instead of the challenge.
    public const byte Hello = 0x01;   // c->s: u8 version, str8 deviceName, 32 deviceId, 32 nonceC
    public const byte Welcome = 0x02; // s->c: u8 status, str8 serverName, str8 os [, str8 reason]
    public const byte Ping = 0x03;    // c->s
    public const byte Pong = 0x04;    // s->c
    public const byte AuthChallenge = 0x05; // s->c: 32 pcId, 32 nonceS, sig8 PC signature (see Auth.cs)
    public const byte AuthResponse = 0x06;  // c->s: sig8 phone signature
    public const byte PairRequest = 0x07;   // c->s (TLS only): u8 version, bytes8 token, str8 deviceName, bytes16 phone public key
    public const byte PairResult = 0x08;    // s->c: u8 status, str8 serverName, str8 os, bytes16 PC public key

    // Pointer
    public const byte Move = 0x10;    // i16 dx, i16 dy (pixels)
    public const byte Button = 0x11;  // u8 button, u8 action
    public const byte Scroll = 0x12;  // i16 vertical, i16 horizontal (120 = one wheel notch, +v = up, +h = right)
    public const byte Zoom = 0x13;    // i8 steps (+ = zoom in)

    // Keyboard
    public const byte Key = 0x20;     // u8 RemoteKey
    public const byte Text = 0x21;    // str16 utf-8 text
    public const byte Action = 0x22;  // u8 RemoteAction
    public const byte Combo = 0x23;   // str8 key combo, e.g. "Ctrl+Shift+Esc" (see Input/KeyNames.cs)
    public const byte ComboDown = 0x24; // str8 combo: press and keep held (auto-repeat) until ComboUp
    public const byte ComboUp = 0x25;   // release the held combo

    // Media: a separate Wi-Fi connection that the phone opens next to the control connection.
    // c->s first frame: the Hello fields + u8 kind, u8 quality, u8 fps [, u8 codecs], then the same authentication.
    // After an OK Welcome, the server only sends *large* frames: [u32 length BE][u8 type][payload].
    public const byte MediaHello = 0x30;
    public const byte ScreenFrame = 0x31;  // i32 left, top, width, height (screen rect in cursor coordinates), JPEG bytes
    public const byte CursorPos = 0x32;    // i32 x, i32 y (cursor coordinates)
    public const byte AudioFormat = 0x33;  // i32 sample rate, u8 channels (16-bit little-endian PCM follows)
    public const byte AudioData = 0x34;    // PCM bytes
    public const byte MediaKeepAlive = 0x35; // empty; sent when nothing else was sent for a second
    public const byte VideoFormat = 0x36;  // i32 left, top, width, height (screen rect), i32 video width, height, u8 codec
    public const byte VideoFrame = 0x37;   // u8 flags (1 = keyframe), H.264 Annex-B bytes
    public const byte KeyframeRequest = 0x38; // c->s on the screen connection (normal small frame): send a keyframe

    // MediaHello's optional last byte: codecs the phone can decode (bit flags). Absent = JPEG only.
    public const byte CodecJpeg = 1;
    public const byte CodecH264 = 2;

    public const byte MediaKindScreen = 1;
    public const byte MediaKindAudio = 2;

    public const byte StatusOk = 0;
    public const byte StatusBadVersion = 2;
    public const byte StatusUnavailable = 3; // Welcome is followed by str8 reason after the usual fields
    public const byte StatusNotPaired = 4;   // this phone isn't paired (or was removed): scan the QR code again
    public const byte StatusAuthFailed = 5;  // signature didn't verify
    public const byte StatusPairingClosed = 6; // pairing code expired or already used: show a new QR code

    /// <summary>
    /// The name phones show for this computer: the computer's name, or AIRPOINTER_NAME if set
    /// (e.g. for screenshots or demos).
    /// </summary>
    public static string ComputerName =>
        Environment.GetEnvironmentVariable("AIRPOINTER_NAME") is { Length: > 0 } name ? name : Environment.MachineName;

    public static string OsName =>
        OperatingSystem.IsWindows() ? "Windows" : OperatingSystem.IsMacOS() ? "macOS" : "Linux";
}

public enum MouseButton : byte { Left = 1, Right = 2, Middle = 3 }

public enum ButtonAction : byte { Up = 0, Down = 1, Click = 2 }

public enum RemoteKey : byte
{
    Enter = 1, Backspace = 2, Escape = 3, Tab = 4, Space = 5, Delete = 6,
    Left = 7, Right = 8, Up = 9, Down = 10, PageUp = 11, PageDown = 12, Home = 13, End = 14,
    VolumeUp = 20, VolumeDown = 21, Mute = 22, PlayPause = 23, NextTrack = 24, PrevTrack = 25,
    F5 = 30,
}

public enum RemoteAction : byte { TaskView = 1, ShowDesktop = 2, NextApp = 3, PrevApp = 4 }

public readonly record struct Frame(byte Type, byte[] Payload);

public sealed class FrameReader(Stream stream)
{
    private readonly byte[] _header = new byte[2];

    /// <summary>Reads the next frame, or returns null when the peer closed the connection.</summary>
    public Frame? Read()
    {
        try
        {
            stream.ReadExactly(_header);
        }
        catch (EndOfStreamException)
        {
            return null;
        }
        int len = BinaryPrimitives.ReadUInt16BigEndian(_header);
        if (len < 1) throw new IOException("Invalid frame length");
        var body = new byte[len];
        stream.ReadExactly(body);
        return new Frame(body[0], body[1..]);
    }
}

public static class FrameWriter
{
    public static void Write(Stream stream, byte type, ReadOnlySpan<byte> payload)
    {
        var buf = new byte[3 + payload.Length];
        BinaryPrimitives.WriteUInt16BigEndian(buf, (ushort)(1 + payload.Length));
        buf[2] = type;
        payload.CopyTo(buf.AsSpan(3));
        stream.Write(buf);
        stream.Flush();
    }

    /// <summary>Large media frame: [u32 length][u8 type][header][body], so screen images can exceed 64 KB.</summary>
    public static void WriteLarge(Stream stream, byte type, ReadOnlySpan<byte> header, ReadOnlySpan<byte> body = default)
    {
        Span<byte> prefix = stackalloc byte[5];
        BinaryPrimitives.WriteUInt32BigEndian(prefix, (uint)(1 + header.Length + body.Length));
        prefix[4] = type;
        stream.Write(prefix);
        stream.Write(header);
        stream.Write(body);
        stream.Flush();
    }

    /// <summary>u8 length + bytes (signatures, tokens).</summary>
    public static byte[] Bytes8(ReadOnlySpan<byte> b)
    {
        if (b.Length > 255) throw new ArgumentException("too long");
        var r = new byte[b.Length + 1];
        r[0] = (byte)b.Length;
        b.CopyTo(r.AsSpan(1));
        return r;
    }

    /// <summary>u16 length + bytes (public keys).</summary>
    public static byte[] Bytes16(ReadOnlySpan<byte> b)
    {
        var r = new byte[b.Length + 2];
        BinaryPrimitives.WriteUInt16BigEndian(r, (ushort)b.Length);
        b.CopyTo(r.AsSpan(2));
        return r;
    }

    public static byte[] Str8(string s)
    {
        var bytes = Encoding.UTF8.GetBytes(s);
        if (bytes.Length > 255) bytes = bytes[..255];
        var r = new byte[bytes.Length + 1];
        r[0] = (byte)bytes.Length;
        bytes.CopyTo(r, 1);
        return r;
    }
}

public sealed class PayloadReader(byte[] data)
{
    private int _pos;

    public byte U8() => data[_pos++];

    public int Remaining => data.Length - _pos;

    public short I16()
    {
        var v = BinaryPrimitives.ReadInt16BigEndian(data.AsSpan(_pos, 2));
        _pos += 2;
        return v;
    }

    public string Str8()
    {
        int len = U8();
        var s = Encoding.UTF8.GetString(data, _pos, len);
        _pos += len;
        return s;
    }

    public byte[] Bytes(int count)
    {
        var b = data.AsSpan(_pos, count).ToArray();
        _pos += count;
        return b;
    }

    public byte[] Bytes8() => Bytes(U8());

    public byte[] Bytes16()
    {
        int len = BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(_pos, 2));
        _pos += 2;
        return Bytes(len);
    }

    public string Str16()
    {
        int len = BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(_pos, 2));
        _pos += 2;
        var s = Encoding.UTF8.GetString(data, _pos, len);
        _pos += len;
        return s;
    }
}
