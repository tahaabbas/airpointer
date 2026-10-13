using System.Buffers.Text;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace AirPointer.Core;

/// <summary>
/// P/Invoke into native/airlink (Rust + iroh): peer-to-peer QUIC with NAT hole punching and relay fallback.
/// See native/airlink/src/lib.rs for the meaning of each call.
/// </summary>
internal static partial class Airlink
{
    private const string Lib = "airlink";

    [LibraryImport(Lib, EntryPoint = "airlink_start")] public static partial ulong Start(ReadOnlySpan<byte> secret, int listen);
    [LibraryImport(Lib, EntryPoint = "airlink_id")] public static partial int Id(ulong node, Span<byte> id);
    [LibraryImport(Lib, EntryPoint = "airlink_online")] public static partial int Online(ulong node, uint timeoutMs);
    [LibraryImport(Lib, EntryPoint = "airlink_accept")] public static partial long Accept(ulong node, uint timeoutMs, Span<byte> remote);
    [LibraryImport(Lib, EntryPoint = "airlink_read")] public static partial long Read(ulong pipe, Span<byte> buf, nuint len, uint timeoutMs);
    [LibraryImport(Lib, EntryPoint = "airlink_write")] public static partial int Write(ulong pipe, ReadOnlySpan<byte> buf, nuint len, uint timeoutMs);
    [LibraryImport(Lib, EntryPoint = "airlink_direct")] public static partial int Direct(ulong pipe);
    [LibraryImport(Lib, EntryPoint = "airlink_close")] public static partial void Close(ulong pipe);
    [LibraryImport(Lib, EntryPoint = "airlink_stop")] public static partial void Stop(ulong node);
    [LibraryImport(Lib, EntryPoint = "airlink_last_error")] private static partial nuint LastError(Span<byte> buf, nuint len);

    // H.264 encoder for screen view (Windows only; see native/airlink/src/video_win.rs).
    [LibraryImport(Lib, EntryPoint = "airvideo_new")] public static partial IntPtr VideoNew(uint width, uint height, uint fps, uint bitrate, uint codec);
    [LibraryImport(Lib, EntryPoint = "airvideo_encode")]
    public static partial int VideoEncode(IntPtr encoder, IntPtr bgra, nuint stride, int keyframe, out IntPtr data, out nuint length, out int isKey);
    [LibraryImport(Lib, EntryPoint = "airvideo_free")] public static partial void VideoFree(IntPtr encoder);

    public static string LastErrorMessage()
    {
        Span<byte> buf = stackalloc byte[512];
        int n = (int)Math.Min(LastError(buf, (nuint)buf.Length), (nuint)buf.Length);
        return Encoding.UTF8.GetString(buf[..n]);
    }
}

/// <summary>One iroh stream as a .NET <see cref="Stream"/>, so <see cref="Session"/> runs over it unchanged.</summary>
internal sealed class AirlinkStream(ulong pipe) : Stream
{
    private int _closed;

    public override int ReadTimeout { get; set; } = 20000; // the phone pings every 2 s
    public override int WriteTimeout { get; set; } = 10000;
    public override bool CanTimeout => true;

    /// <summary>True when the connection runs over a direct (hole-punched) path, false when relayed.</summary>
    public bool IsDirect => Airlink.Direct(pipe) == 1;

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        if (buffer.IsEmpty) return 0;
        long n = Airlink.Read(pipe, buffer, (nuint)buffer.Length, (uint)ReadTimeout);
        return n switch
        {
            >= 0 => (int)n, // 0 = end of stream
            -2 => throw new IOException("Internet connection timed out"),
            _ => throw new IOException("Internet connection lost"),
        };
    }

    public override void Write(byte[] buffer, int offset, int count) => Write(buffer.AsSpan(offset, count));

    public override void Write(ReadOnlySpan<byte> buffer)
    {
        if (buffer.IsEmpty) return;
        if (Volatile.Read(ref _closed) != 0) throw new ObjectDisposedException(nameof(AirlinkStream));
        if (Airlink.Write(pipe, buffer, (nuint)buffer.Length, (uint)WriteTimeout) != 0)
            throw new IOException("Internet connection lost");
    }

    public override void Flush() { } // QUIC sends as soon as data is written

    protected override void Dispose(bool disposing)
    {
        if (Interlocked.Exchange(ref _closed, 1) == 0) Airlink.Close(pipe);
        base.Dispose(disposing);
    }

    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}

/// <summary>
/// Lets paired phones connect over the internet. An iroh endpoint (key in internet.key) publishes how to reach
/// this computer; phones dial it by its id, directly through NAT when possible, else via a public relay.
/// Every connection then goes through the same authentication as Wi-Fi (Auth.cs).
/// </summary>
internal sealed class InternetServer(ServerHost host) : IDisposable
{
    private ulong _node;
    private volatile bool _stopping;

    /// <summary>This computer's iroh endpoint id: what phones dial. Learned by phones on every local connection.</summary>
    public byte[] Id { get; } = new byte[32];

    public string IdText => Base64Url.EncodeToString(Id);

    private static string KeyPath => Path.Combine(AppConfig.Dir, "internet.key");

    /// <summary>Starts the endpoint; throws if the native library is missing or binding fails.</summary>
    public void Start(Action<string> status)
    {
        _node = Airlink.Start(LoadOrCreateKey(), listen: 1);
        if (_node == 0) throw new InvalidOperationException(Airlink.LastErrorMessage());
        Airlink.Id(_node, Id);
        new Thread(() =>
        {
            status("Connecting to the internet…");
            status(Airlink.Online(_node, 30000) == 1
                ? "On: paired phones can connect from anywhere"
                : "Waiting for internet access (paired phones can still connect on Wi-Fi)");
            AcceptLoop();
        }) { IsBackground = true, Name = "internet-accept" }.Start();
    }

    private static byte[] LoadOrCreateKey()
    {
        try
        {
            var existing = File.ReadAllBytes(KeyPath);
            if (existing.Length == 32) return existing;
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        var key = RandomNumberGenerator.GetBytes(32);
        Directory.CreateDirectory(AppConfig.Dir);
        File.WriteAllBytes(KeyPath, key);
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(KeyPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        return key;
    }

    private void AcceptLoop()
    {
        var remote = new byte[32];
        while (!_stopping)
        {
            long pipe = Airlink.Accept(_node, 1000, remote);
            if (pipe < 0) return; // stopped
            if (pipe == 0) continue;
            var remoteKey = "internet " + Base64Url.EncodeToString(remote)[..8];
            var stream = new AirlinkStream((ulong)pipe);
            new Thread(() =>
            {
                using (stream) new Session(stream, "Internet", remoteKey, host).Run();
            }) { IsBackground = true, Name = "internet-session" }.Start();
        }
    }

    public void Dispose()
    {
        _stopping = true;
        if (_node != 0) Airlink.Stop(_node);
        _node = 0;
    }
}
