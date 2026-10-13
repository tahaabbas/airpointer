using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace AirPointer.Media;

/// <summary>Records what the computer is playing, as 16-bit little-endian stereo PCM.</summary>
public interface IAudioCapture : IDisposable
{
    int SampleRate { get; }

    /// <summary>Blocks until some audio is available; returns 0 after a while of silence (nothing playing).</summary>
    int Read(byte[] buffer);
}

public static class AudioCapture
{
    public static IAudioCapture Create()
    {
        if (OperatingSystem.IsWindows()) return new WindowsLoopbackCapture();
        if (OperatingSystem.IsLinux()) return new PulseMonitorCapture();
        throw new PlatformNotSupportedException("Computer sound isn't supported on macOS yet.");
    }
}

/// <summary>WASAPI loopback of the default playback device (what you'd hear from the speakers).</summary>
[SupportedOSPlatform("windows")]
internal sealed unsafe class WindowsLoopbackCapture : IAudioCapture
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        void EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        void GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        void Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        void Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        void GetBufferSize(out uint frames);
        void GetStreamLatency(out long latency);
        void GetCurrentPadding(out uint frames);
        void IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        void GetMixFormat(out IntPtr format);
        void GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        void Start();
        void Stop();
        void Reset();
        void SetEventHandle(IntPtr handle);
        void GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        void GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        void ReleaseBuffer(uint frames);
        void GetNextPacketSize(out uint frames);
    }

    private const int ERender = 0, EConsole = 0, ClsctxAll = 0x17;
    private const int SharedMode = 0, StreamFlagsLoopback = 0x00020000;
    private const uint BufferFlagsSilent = 0x2;
    private static readonly Guid FloatSubFormat = new("00000003-0000-0010-8000-00aa00389b71");

    private enum SampleType { Float32, Int16, Int32 }

    private readonly IAudioClient _client;
    private readonly IAudioCaptureClient _capture;
    private readonly int _channels;
    private readonly SampleType _type;

    public int SampleRate { get; }

    public WindowsLoopbackCapture()
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        enumerator.GetDefaultAudioEndpoint(ERender, EConsole, out var device);
        var clientId = typeof(IAudioClient).GUID;
        device.Activate(ref clientId, ClsctxAll, IntPtr.Zero, out var client);
        _client = (IAudioClient)client;

        _client.GetMixFormat(out IntPtr format);
        try
        {
            // WAVEFORMATEX: tag, channels, rate, avgBytes, blockAlign, bits, cbSize [, extensible fields]
            ushort tag = *(ushort*)format;
            _channels = *(ushort*)(format + 2);
            SampleRate = *(int*)(format + 4);
            int bits = *(ushort*)(format + 14);
            bool isFloat = tag == 3 || (tag == 0xFFFE && *(Guid*)(format + 24) == FloatSubFormat);
            _type = (isFloat, bits) switch
            {
                (true, 32) => SampleType.Float32,
                (false, 16) => SampleType.Int16,
                (false, 32) => SampleType.Int32,
                _ => throw new NotSupportedException($"Unsupported sound format ({bits}-bit)."),
            };
            _client.Initialize(SharedMode, StreamFlagsLoopback, 2_000_000 /* 200 ms */, 0, format, IntPtr.Zero);
        }
        finally
        {
            Marshal.FreeCoTaskMem(format);
        }

        var captureId = typeof(IAudioCaptureClient).GUID;
        _client.GetService(ref captureId, out var capture);
        _capture = (IAudioCaptureClient)capture;
        _client.Start();
    }

    public int Read(byte[] buffer)
    {
        long giveUp = Environment.TickCount64 + 1000;
        int written = 0;
        while (true)
        {
            _capture.GetNextPacketSize(out uint packet);
            while (packet > 0 && written + packet * 4 <= buffer.Length)
            {
                _capture.GetBuffer(out IntPtr data, out uint frames, out uint flags, out _, out _);
                Convert(data, (int)frames, (flags & BufferFlagsSilent) != 0, buffer, written);
                written += (int)frames * 4;
                _capture.ReleaseBuffer(frames);
                _capture.GetNextPacketSize(out packet);
            }
            if (written > 0) return written;
            // Windows delivers nothing at all while no app is playing sound.
            if (Environment.TickCount64 > giveUp) return 0;
            Thread.Sleep(5);
        }
    }

    /// <summary>Mix format (usually float, any channel count) → 16-bit stereo.</summary>
    private void Convert(IntPtr data, int frames, bool silent, byte[] buffer, int offset)
    {
        fixed (byte* b = &buffer[offset])
        {
            short* dst = (short*)b;
            if (silent)
            {
                new Span<short>(dst, frames * 2).Clear();
                return;
            }
            int right = _channels > 1 ? 1 : 0;
            for (int i = 0; i < frames; i++)
            {
                int s = i * _channels;
                dst[i * 2] = Sample(data, s);
                dst[i * 2 + 1] = Sample(data, s + right);
            }
        }
    }

    private short Sample(IntPtr data, int index) => _type switch
    {
        SampleType.Float32 => (short)(Math.Clamp(((float*)data)[index], -1f, 1f) * 32767f),
        SampleType.Int16 => ((short*)data)[index],
        _ => (short)(((int*)data)[index] >> 16),
    };

    public void Dispose()
    {
        try { _client.Stop(); } catch { /* device gone */ }
        Marshal.ReleaseComObject(_capture);
        Marshal.ReleaseComObject(_client);
    }
}

/// <summary>Linux: records the default output's monitor source with parec (PulseAudio or PipeWire).</summary>
[SupportedOSPlatform("linux")]
internal sealed class PulseMonitorCapture : IAudioCapture
{
    private readonly Process _process;

    public int SampleRate => 48000;

    public PulseMonitorCapture()
    {
        try
        {
            _process = Process.Start(new ProcessStartInfo("parec",
                "--raw --format=s16le --rate=48000 --channels=2 --latency-msec=20 --device=@DEFAULT_MONITOR@")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            }) ?? throw new InvalidOperationException();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            throw new PlatformNotSupportedException("Computer sound needs \"parec\" (install pulseaudio-utils).");
        }
    }

    public int Read(byte[] buffer)
    {
        // parec streams continuously (silence included); hand out whole 20 ms chunks.
        const int chunk = 3840;
        int n = _process.StandardOutput.BaseStream.ReadAtLeast(buffer.AsSpan(0, chunk), chunk, throwOnEndOfStream: false);
        if (n < chunk) throw new IOException("parec stopped: " + _process.StandardError.ReadToEnd().Trim());
        return n;
    }

    public void Dispose()
    {
        try { _process.Kill(); } catch { /* already gone */ }
        _process.Dispose();
    }
}
