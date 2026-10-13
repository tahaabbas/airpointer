using System.Buffers.Binary;
using System.Diagnostics;
using System.Net.Sockets;
using AirPointer.Media;
using SkiaSharp;

namespace AirPointer.Core;

/// <summary>
/// A phone's screen-view or sound connection: a second Wi-Fi connection next to its control
/// <see cref="Session"/>. It only sends (large frames); the phone closes it to stop.
/// </summary>
public sealed class MediaSession
{
    private readonly Stream _stream;
    private readonly FrameReader _reader;
    private readonly string _transport;
    private readonly ServerHost _host;

    public string DeviceName { get; private set; } = "?";
    public string DeviceId { get; private set; } = "";
    public string Transport => _transport;
    public byte Kind { get; private set; }

    public MediaSession(Stream stream, FrameReader reader, string transport, ServerHost host)
    {
        _stream = stream;
        _reader = reader;
        _transport = transport;
        _host = host;
    }

    public string What => Kind == Protocol.MediaKindScreen ? "screen view" : "sound";

    /// <summary>Runs until the phone disconnects (an IOException ends it, handled by <see cref="Session"/>).</summary>
    public void Run(byte[] hello)
    {
        var p = new PayloadReader(hello);
        if (Session.ReadHello(p, _stream, _host, _transport) is not { } fields) return;
        Kind = p.U8();
        int quality = p.U8();
        int fps = p.U8();
        int codecs = p.Remaining > 0 ? p.U8() : Protocol.CodecJpeg; // phones before H.264 only know JPEG
        if (Auth.Authenticate(_stream, _reader, _host, fields.Id, fields.Nonce, fields.Name, _transport) is not { } device) return;
        DeviceName = device.Name;
        DeviceId = device.Id;

        string? refusal =
            Kind == Protocol.MediaKindScreen && !_host.Config.AllowScreenView ? "Screen sharing is turned off on the computer."
            : Kind == Protocol.MediaKindAudio && !_host.Config.AllowSound ? "Sound sharing is turned off on the computer."
            : _transport is not ("Wi-Fi" or "Internet") ? "Screen view and sound need Wi-Fi or an internet connection."
            : Kind is not (Protocol.MediaKindScreen or Protocol.MediaKindAudio) ? "Unknown request."
            : null;
        if (refusal != null)
        {
            Session.WriteWelcome(_stream, Protocol.StatusUnavailable, refusal);
            return;
        }

        IDisposable source;
        try
        {
            source = Kind == Protocol.MediaKindScreen ? ScreenCapture.Create() : AudioCapture.Create();
        }
        catch (Exception e)
        {
            Session.WriteWelcome(_stream, Protocol.StatusUnavailable, e.Message);
            _host.Log($"{DeviceName}: {What} unavailable: {e.Message}");
            return;
        }

        using (source)
        {
            Session.WriteWelcome(_stream, Protocol.StatusOk);
            if (_stream.CanTimeout) _stream.WriteTimeout = 10000; // a vanished phone ends the stream
            var output = new BufferedStream(_stream, 64 * 1024);
            _host.AddMedia(this);
            try
            {
                if (source is IScreenCapture screen) StreamScreen(screen, output, quality, fps, codecs);
                else StreamAudio((IAudioCapture)source, output);
            }
            catch (Exception e) when (e is not (IOException or SocketException or ObjectDisposedException))
            {
                // Capture failed (e.g. sound device unplugged): end this stream; the phone retries.
                _host.Log($"{DeviceName}: {What} stopped: {e.Message}");
            }
            finally
            {
                _host.RemoveMedia(this);
            }
        }
    }

    public void Close()
    {
        try { _stream.Dispose(); } catch { /* ignore */ }
    }

    /// <summary>
    /// Sends the cursor position whenever it moves (~60 Hz) and the screen at the requested frame rate, but
    /// only when the picture changed: as H.264 video when the phone can decode it and this computer can encode
    /// it (Windows), else as JPEG images. Capturing (this thread) and encoding + sending
    /// (<see cref="FrameEncoder"/>) overlap, so the frame rate is limited by the slower of the two. A slow
    /// phone/network lowers the frame rate instead of building a queue: only the newest frame waits.
    /// </summary>
    private void StreamScreen(IScreenCapture capture, Stream output, int quality, int fps, int codecs)
    {
        int frameMs = 1000 / Math.Clamp(fps, 1, 60);
        var writer = new MediaWriter(output);
        bool h264 = (codecs & Protocol.CodecH264) != 0 && VideoEncoders.Available(_host.Config);
        using var encoder = new FrameEncoder(writer, quality, fps, h264, _host.Log, DeviceName, _host.Config);
        StartKeyframeRequestReader(encoder);
        var clock = Stopwatch.StartNew();
        long nextFrame = 0, lastSubmit = 0;
        int refinements = 0;
        (int X, int Y)? lastCursor = null;
        byte[] previous = [];
        ScreenRect previousRect = default;
        int previousWidth = 0, previousHeight = 0, previousStride = 0;
        var cursorMsg = new byte[8];

        while (true)
        {
            encoder.ThrowIfFailed();
            long now = clock.ElapsedMilliseconds;
            var cursor = capture.CursorPosition();
            if (cursor != lastCursor)
            {
                lastCursor = cursor;
                BinaryPrimitives.WriteInt32BigEndian(cursorMsg, cursor.X);
                BinaryPrimitives.WriteInt32BigEndian(cursorMsg.AsSpan(4), cursor.Y);
                writer.Write(Protocol.CursorPos, cursorMsg);
                // Crossed onto another monitor: show it right away instead of at the next frame.
                if (previousRect.Width > 0 && !previousRect.Contains(cursor.X, cursor.Y)) nextFrame = now;
            }

            if (now >= nextFrame)
            {
                nextFrame = now + frameMs;
                using var shot = capture.Capture();
                if (shot != null)
                {
                    var pixels = shot.Bytes;
                    if (shot.Rect != previousRect || !pixels.SequenceEqual(previous))
                    {
                        if (previous.Length != pixels.Length) previous = new byte[pixels.Length];
                        pixels.CopyTo(previous);
                        (previousRect, previousWidth, previousHeight, previousStride) = (shot.Rect, shot.Width, shot.Height, shot.Stride);
                        encoder.Submit(pixels, shot.Width, shot.Height, shot.Stride, shot.Rect);
                        lastSubmit = now;
                        refinements = 3;
                    }
                    else if (encoder.IsVideo && (encoder.KeyframeRequested || (refinements > 0 && now - lastSubmit >= 120)))
                    {
                        // Unchanged picture: video still needs a keyframe when the phone asks (it just joined or its
                        // decoder restarted), and a few more encodes of a still image sharpen what motion blurred.
                        encoder.Submit(previous, previousWidth, previousHeight, previousStride, previousRect);
                        lastSubmit = now;
                        if (refinements > 0) refinements--;
                    }
                }
            }

            writer.KeepAliveIfIdle();
            long wait = Math.Min(nextFrame - clock.ElapsedMilliseconds, 15);
            if (wait > 0) Thread.Sleep((int)wait);
        }
    }

    /// <summary>The phone sends KeyframeRequest on the screen connection when its video decoder needs a fresh start.</summary>
    private void StartKeyframeRequestReader(FrameEncoder encoder)
    {
        if (_stream.CanTimeout) _stream.ReadTimeout = Timeout.Infinite; // the phone only writes when it needs something
        new Thread(() =>
        {
            try
            {
                while (_reader.Read() is { } f)
                    if (f.Type == Protocol.KeyframeRequest) encoder.RequestKeyframe();
            }
            catch (Exception)
            {
                // Connection closed: the sending side notices too.
            }
        }) { IsBackground = true, Name = "screen-requests" }.Start();
    }

    /// <summary>Large-frame writes shared by the capture and encoder threads.</summary>
    private sealed class MediaWriter(Stream output)
    {
        private readonly object _lock = new();
        private long _lastSent = Environment.TickCount64;

        public void Write(byte type, ReadOnlySpan<byte> header, ReadOnlySpan<byte> body = default)
        {
            lock (_lock)
            {
                FrameWriter.WriteLarge(output, type, header, body);
                _lastSent = Environment.TickCount64;
            }
        }

        /// <summary>A static screen sends nothing; a keep-alive each second tells the phone the link is fine.</summary>
        public void KeepAliveIfIdle()
        {
            if (Environment.TickCount64 - Volatile.Read(ref _lastSent) >= 1000) Write(Protocol.MediaKeepAlive, []);
        }
    }

    /// <summary>
    /// Encodes and sends screen images on its own thread, as H.264 (VideoFormat + VideoFrame) or JPEG
    /// (ScreenFrame). <see cref="Submit"/> copies the pixels into a mailbox that holds only the newest image;
    /// one still waiting there is replaced (dropped). Dropping raw frames is fine for video too: only
    /// encoded frames depend on each other.
    /// </summary>
    private sealed class FrameEncoder : IDisposable
    {
        private readonly MediaWriter _writer;
        private readonly SKJpegEncoderOptions _jpeg;
        private readonly int _quality, _fps;
        private readonly Action<string> _log;
        private readonly string _device;
        private readonly AppConfig _config;
        private readonly Thread _thread;
        private readonly object _lock = new();
        private byte[] _pending = [], _working = [];
        private int _width, _height, _stride;
        private ScreenRect _rect;
        private bool _hasPending, _stopped;
        private volatile bool _video, _keyframeRequested;
        private Exception? _failure;

        // Video encoder state (encoder thread only).
        private IVideoEncoder? _encoder;
        private int _encWidth, _encHeight;
        private ScreenRect _encRect;

        public FrameEncoder(MediaWriter writer, int quality, int fps, bool h264, Action<string> log, string device, AppConfig config)
        {
            _config = config;
            _writer = writer;
            _quality = quality;
            _fps = fps;
            _video = h264;
            _log = log;
            _device = device;
            _jpeg = quality switch
            {
                0 => new SKJpegEncoderOptions(45, SKJpegEncoderDownsample.Downsample420, SKJpegEncoderAlphaOption.Ignore),
                2 => new SKJpegEncoderOptions(90, SKJpegEncoderDownsample.Downsample444, SKJpegEncoderAlphaOption.Ignore),
                _ => new SKJpegEncoderOptions(70, SKJpegEncoderDownsample.Downsample420, SKJpegEncoderAlphaOption.Ignore),
            };
            _thread = new Thread(Loop) { IsBackground = true, Name = "screen-encoder" };
            _thread.Start();
        }

        /// <summary>True while sending H.264 (false = JPEG, e.g. after the encoder failed to start).</summary>
        public bool IsVideo => _video;

        public bool KeyframeRequested => _keyframeRequested;

        public void RequestKeyframe() => _keyframeRequested = true;

        public void Submit(ReadOnlySpan<byte> pixels, int width, int height, int stride, ScreenRect rect)
        {
            lock (_lock)
            {
                if (_pending.Length != pixels.Length) _pending = new byte[pixels.Length];
                pixels.CopyTo(_pending);
                (_width, _height, _stride, _rect) = (width, height, stride, rect);
                _hasPending = true;
                Monitor.Pulse(_lock);
            }
        }

        /// <summary>Rethrows (on the capture thread) a send failure, e.g. the phone went away.</summary>
        public void ThrowIfFailed()
        {
            var f = Volatile.Read(ref _failure);
            if (f != null) throw f is IOException ? new IOException(f.Message, f) : new InvalidOperationException(f.Message, f);
        }

        private unsafe void Loop()
        {
            var header = new byte[16];
            try
            {
                while (true)
                {
                    int width, height, stride;
                    ScreenRect r;
                    lock (_lock)
                    {
                        while (!_hasPending && !_stopped) Monitor.Wait(_lock);
                        if (_stopped) return;
                        (_pending, _working) = (_working, _pending);
                        (width, height, stride, r) = (_width, _height, _stride, _rect);
                        _hasPending = false;
                    }
                    fixed (byte* pixels = _working)
                    {
                        if (_video && EncodeVideo((IntPtr)pixels, width, height, stride, r)) continue;
                        var info = new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Opaque);
                        using var pixmap = new SKPixmap(info, (IntPtr)pixels, stride);
                        using var data = pixmap.Encode(_jpeg);
                        if (data == null) continue;
                        BinaryPrimitives.WriteInt32BigEndian(header, r.X);
                        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(4), r.Y);
                        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(8), r.Width);
                        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(12), r.Height);
                        _writer.Write(Protocol.ScreenFrame, header, data.AsSpan());
                    }
                }
            }
            catch (Exception e)
            {
                Volatile.Write(ref _failure, e);
            }
            finally
            {
                FreeEncoder();
            }
        }

        /// <summary>H.264 path. Returns false if video isn't possible (then this and later frames go as JPEG).</summary>
        private bool EncodeVideo(IntPtr pixels, int width, int height, int stride, ScreenRect rect)
        {
            int w = width & ~1, h = height & ~1;
            bool key = _keyframeRequested;
            if (_encoder == null || w != _encWidth || h != _encHeight)
            {
                FreeEncoder();
                try
                {
                    _encoder = VideoEncoders.Create(_config, w, h, Math.Clamp(_fps, 5, 60), Bitrate(w, h));
                }
                catch (Exception e) when (e is not IOException)
                {
                    _video = false;
                    _log($"{_device}: H.264 unavailable, using JPEG for screen view. {e.Message}");
                    return false;
                }
                (_encWidth, _encHeight) = (w, h);
                _encRect = default; // send the format below
                key = true;
            }
            if (rect != _encRect)
            {
                // VideoFormat: screen rect (cursor coordinates), video size, codec.
                var format = new byte[25];
                BinaryPrimitives.WriteInt32BigEndian(format, rect.X);
                BinaryPrimitives.WriteInt32BigEndian(format.AsSpan(4), rect.Y);
                BinaryPrimitives.WriteInt32BigEndian(format.AsSpan(8), rect.Width);
                BinaryPrimitives.WriteInt32BigEndian(format.AsSpan(12), rect.Height);
                BinaryPrimitives.WriteInt32BigEndian(format.AsSpan(16), w);
                BinaryPrimitives.WriteInt32BigEndian(format.AsSpan(20), h);
                format[24] = Protocol.CodecH264;
                _writer.Write(Protocol.VideoFormat, format);
                _encRect = rect;
                key = true;
            }
            _keyframeRequested = false;
            _encoder.Encode(pixels, stride, key, out var data, out int length, out bool isKey);
            if (length > 0)
            {
                ReadOnlySpan<byte> flags = [(byte)(isKey ? 1 : 0)];
                unsafe { _writer.Write(Protocol.VideoFrame, flags, new ReadOnlySpan<byte>((void*)data, length)); }
            }
            return true;
        }

        /// <summary>Bits per second for the chosen quality, scaled from 3 / 6 / 12 Mbit/s at 1080p.</summary>
        private uint Bitrate(int w, int h)
        {
            double mbps = _quality switch { 0 => 3, 2 => 12, _ => 6 } * (w * (double)h / (1920 * 1080));
            return (uint)(Math.Clamp(mbps, 1, 40) * 1_000_000);
        }

        private void FreeEncoder()
        {
            _encoder?.Dispose();
            _encoder = null;
        }

        public void Dispose()
        {
            lock (_lock)
            {
                _stopped = true;
                Monitor.Pulse(_lock);
            }
            _thread.Join(2000);
        }
    }

    private static void StreamAudio(IAudioCapture capture, Stream output)
    {
        var format = new byte[5];
        BinaryPrimitives.WriteInt32BigEndian(format, capture.SampleRate);
        format[4] = 2;
        FrameWriter.WriteLarge(output, Protocol.AudioFormat, format);

        var buffer = new byte[32 * 1024];
        while (true)
        {
            int n = capture.Read(buffer);
            if (n > 0) FrameWriter.WriteLarge(output, Protocol.AudioData, buffer.AsSpan(0, n));
            else FrameWriter.WriteLarge(output, Protocol.MediaKeepAlive, ReadOnlySpan<byte>.Empty);
        }
    }
}
