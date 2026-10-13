using System.Runtime.InteropServices;
using System.Security.Cryptography;
using AirPointer.Core;
using ICSharpCode.SharpZipLib.BZip2;

namespace AirPointer.Media;

/// <summary>
/// Cisco's OpenH264 binary: the H.264 encoder for screen view on Linux and macOS.
///
/// Cisco pays the H.264 patent royalties for its binary, on the condition that each user downloads it from
/// Cisco (it may not be bundled), can turn it on and off, and is shown the attribution <see cref="Attribution"/>
/// (see Assets/OpenH264-BINARY_LICENSE.txt). So it is downloaded only when the user turns it on, straight from
/// Cisco, and checked against the hash of the exact release this app was built and tested with.
/// </summary>
public static class OpenH264
{
    public const string Attribution = "OpenH264 Video Codec provided by Cisco Systems, Inc.";
    public const string Version = "2.6.0";

    private sealed record Binary(string File, string Sha256Bz2);

    /// <summary>Cisco's v2.6.0 binaries (SHA-256 of the .bz2 download).</summary>
    private static Binary? ForThisComputer()
    {
        var arch = RuntimeInformation.OSArchitecture;
        if (OperatingSystem.IsLinux() && arch == Architecture.X64)
            return new("libopenh264-2.6.0-linux64.8.so", "27ab53323c110b76214c1c72222f459d17febbcd1e252136cadc292b0308d75b");
        if (OperatingSystem.IsLinux() && arch == Architecture.Arm64)
            return new("libopenh264-2.6.0-linux-arm64.8.so", "a78aea7970150f46bcd3bb7994c9e6dd90bd7a9ea785920f5a73f6964e3fcda7");
        if (OperatingSystem.IsMacOS() && arch == Architecture.Arm64)
            return new("libopenh264-2.6.0-mac-arm64.dylib", "6db362ee5abdab572311aeadb96d3f44b0617d9a4a4b9f4db4cb5ac4d968da71");
        if (OperatingSystem.IsMacOS() && arch == Architecture.X64)
            return new("libopenh264-2.6.0-mac-x64.dylib", "38b2ed6d1d45b6a3e408c734173f2d67ab44a10d0e154ff3489b89877cd60e7e");
        if (OperatingSystem.IsWindows() && arch == Architecture.X64) // only for testing (Windows uses Media Foundation)
            return new("openh264-2.6.0-win64.dll", "dab5f2a872777f9a58b69bfa9fbcf20d9f82f2d6ec91383fd70bff49bd34ac9f");
        return null;
    }

    public static bool Supported => ForThisComputer() != null;

    private static string? LocalPath => ForThisComputer() is { } b ? Path.Combine(AppConfig.Dir, "openh264", b.File) : null;

    public static bool IsDownloaded => LocalPath is { } p && File.Exists(p);

    /// <summary>Downloads Cisco's binary (about 0.5 MB), verifies it and unpacks it next to the config.</summary>
    public static async Task DownloadAsync(CancellationToken cancel = default)
    {
        var binary = ForThisComputer() ?? throw new PlatformNotSupportedException("OpenH264 isn't available for this computer.");
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("AirPointer");
        var compressed = await http.GetByteArrayAsync($"https://ciscobinary.openh264.org/{binary.File}.bz2", cancel);
        if (!string.Equals(Convert.ToHexString(SHA256.HashData(compressed)), binary.Sha256Bz2, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("The downloaded OpenH264 file doesn't match the expected release; not using it.");

        var path = LocalPath!;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".part";
        using (var input = new MemoryStream(compressed))
        using (var output = File.Create(temp))
            BZip2.Decompress(input, output, isStreamOwner: false);
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(temp, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        File.Move(temp, path, overwrite: true);
    }

    // ---------- native API (codec_api.h, v2.6.0) ----------

    private static readonly object LoadLock = new();
    private static IntPtr _lib;
    private static unsafe delegate* unmanaged<IntPtr*, int> _create;
    private static unsafe delegate* unmanaged<IntPtr, void> _destroy;
    private static unsafe delegate* unmanaged<uint*, void> _version;

    internal static unsafe void EnsureLoaded()
    {
        lock (LoadLock)
        {
            if (_lib != IntPtr.Zero) return;
            var path = LocalPath;
            if (path == null || !File.Exists(path)) throw new InvalidOperationException("OpenH264 isn't downloaded.");
            var lib = NativeLibrary.Load(path);
            _create = (delegate* unmanaged<IntPtr*, int>)NativeLibrary.GetExport(lib, "WelsCreateSVCEncoder");
            _destroy = (delegate* unmanaged<IntPtr, void>)NativeLibrary.GetExport(lib, "WelsDestroySVCEncoder");
            _version = (delegate* unmanaged<uint*, void>)NativeLibrary.GetExport(lib, "WelsGetCodecVersionEx");
            // The struct layouts below are those of 2.6: refuse anything else.
            uint* v = stackalloc uint[4];
            _version(v);
            if (v[0] != 2 || v[1] != 6) throw new InvalidOperationException($"OpenH264 {v[0]}.{v[1]} isn't supported (needs {Version}).");
            _lib = lib;
        }
    }

    internal static unsafe IntPtr CreateEncoder()
    {
        EnsureLoaded();
        IntPtr encoder;
        if (_create(&encoder) != 0 || encoder == IntPtr.Zero) throw new InvalidOperationException("OpenH264 couldn't create an encoder.");
        return encoder;
    }

    internal static unsafe void DestroyEncoder(IntPtr encoder) => _destroy(encoder);
}

/// <summary>Screen-content H.264 with OpenH264. BGRA → I420 conversion here (BT.601 limited range, like video_win.rs).</summary>
internal sealed unsafe class OpenH264Encoder : IVideoEncoder
{
    // ISVCEncoderVtbl order
    private const int Initialize = 0, Uninitialize = 3, EncodeFrame = 4, ForceIntraFrame = 6, SetOption = 7;
    // ENCODER_OPTION values
    private const int OptionDataFormat = 0, OptionIdrInterval = 1, OptionRcFrameSkip = 9, OptionTraceLevel = 25;
    private const int ScreenContentRealTime = 1, RcBitrateMode = 1, VideoFormatI420 = 23, TraceQuiet = 0;
    private const int FrameTypeIdr = 1, FrameTypeI = 2, FrameTypeSkip = 4;
    private const int MaxLayers = 128;

    [StructLayout(LayoutKind.Sequential)]
    private struct SEncParamBase
    {
        public int UsageType, Width, Height, TargetBitrate, RcMode;
        public float MaxFrameRate;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SSourcePicture
    {
        public int ColorFormat;
        public fixed int Stride[4];
        public IntPtr Data0, Data1, Data2, Data3;
        public int Width, Height;
        public long TimeStamp;
        public byte PsnrY, PsnrU, PsnrV;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SLayerBSInfo
    {
        public byte TemporalId, SpatialId, QualityId;
        public int FrameType;
        public byte LayerType;
        public int SubSeqId, NalCount;
        public int* NalLengthInByte;
        public byte* BsBuf;
        public float PsnrY, PsnrU, PsnrV;
    }

    private IntPtr _encoder;
    private readonly IntPtr* _vtbl;
    private readonly int _width, _height;
    private readonly byte[] _i420;
    private readonly byte* _info; // SFrameBSInfo
    private readonly int _infoFrameTypeOffset;
    private byte[] _out = new byte[1 << 20];
    private GCHandle _outPin;
    private readonly long _start = Environment.TickCount64;

    public OpenH264Encoder(int width, int height, int fps, uint bitrate)
    {
        if (sizeof(SLayerBSInfo) != 56) throw new PlatformNotSupportedException("Unexpected OpenH264 struct layout.");
        _width = width;
        _height = height;
        _encoder = OpenH264.CreateEncoder();
        _vtbl = *(IntPtr**)_encoder;
        int quiet = TraceQuiet;
        Call(SetOption, OptionTraceLevel, &quiet);
        var p = new SEncParamBase
        {
            UsageType = ScreenContentRealTime, Width = width, Height = height,
            TargetBitrate = (int)bitrate, RcMode = RcBitrateMode, MaxFrameRate = fps,
        };
        if (((delegate* unmanaged<IntPtr, SEncParamBase*, int>)_vtbl[Initialize])(_encoder, &p) != 0)
        {
            OpenH264.DestroyEncoder(_encoder);
            throw new InvalidOperationException("OpenH264 rejected the encoder settings.");
        }
        int format = VideoFormatI420;
        Call(SetOption, OptionDataFormat, &format);
        int idr = fps * 4;
        Call(SetOption, OptionIdrInterval, &idr);
        byte skip = 0; // encode every frame we hand it (the "sharpen when still" frames too)
        Call(SetOption, OptionRcFrameSkip, &skip);

        _i420 = GC.AllocateArray<byte>(width * height * 3 / 2, pinned: true);
        _infoFrameTypeOffset = 8 + MaxLayers * sizeof(SLayerBSInfo);
        _info = (byte*)NativeMemory.AllocZeroed((nuint)(_infoFrameTypeOffset + 16));
        _outPin = GCHandle.Alloc(_out, GCHandleType.Pinned);
    }

    private int Call(int slot, int option, void* value) =>
        ((delegate* unmanaged<IntPtr, int, void*, int>)_vtbl[slot])(_encoder, option, value);

    public void Encode(IntPtr bgra, int stride, bool keyframe, out IntPtr data, out int length, out bool isKeyframe)
    {
        int w = _width, h = _height;
        fixed (byte* yuv = _i420)
        {
            byte* y = yuv, u = yuv + w * h, v = u + w * h / 4;
            BgraToI420((byte*)bgra, stride, w, h, y, u, v);
            var pic = new SSourcePicture
            {
                ColorFormat = VideoFormatI420, Data0 = (IntPtr)y, Data1 = (IntPtr)u, Data2 = (IntPtr)v,
                Width = w, Height = h, TimeStamp = Environment.TickCount64 - _start,
            };
            pic.Stride[0] = w;
            pic.Stride[1] = w / 2;
            pic.Stride[2] = w / 2;
            if (keyframe) ((delegate* unmanaged<IntPtr, byte, int>)_vtbl[ForceIntraFrame])(_encoder, 1);
            NativeMemory.Clear(_info, (nuint)(_infoFrameTypeOffset + 16));
            if (((delegate* unmanaged<IntPtr, SSourcePicture*, byte*, int>)_vtbl[EncodeFrame])(_encoder, &pic, _info) != 0)
                throw new InvalidOperationException("OpenH264 failed to encode a frame.");
        }

        int frameType = *(int*)(_info + _infoFrameTypeOffset);
        length = 0;
        if (frameType != FrameTypeSkip)
        {
            int layers = *(int*)_info;
            var layer = (SLayerBSInfo*)(_info + 8);
            for (int i = 0; i < layers && i < MaxLayers; i++)
            {
                int size = 0;
                for (int n = 0; n < layer[i].NalCount; n++) size += layer[i].NalLengthInByte[n];
                if (length + size > _out.Length) GrowOutput(length + size);
                new ReadOnlySpan<byte>(layer[i].BsBuf, size).CopyTo(_out.AsSpan(length));
                length += size;
            }
        }
        data = _outPin.AddrOfPinnedObject();
        isKeyframe = frameType is FrameTypeIdr or FrameTypeI;
    }

    private void GrowOutput(int needed)
    {
        var bigger = new byte[Math.Max(needed, _out.Length * 2)];
        _out.CopyTo(bigger, 0);
        _outPin.Free();
        _out = bigger;
        _outPin = GCHandle.Alloc(_out, GCHandleType.Pinned);
    }

    /// <summary>BGRA → I420 (planar), BT.601 limited range; chroma = average of each 2×2 block.</summary>
    private static void BgraToI420(byte* src, int stride, int w, int h, byte* yp, byte* up, byte* vp)
    {
        for (int row = 0; row < h; row++)
        {
            byte* s = src + row * stride;
            byte* d = yp + row * w;
            for (int x = 0; x < w; x++, s += 4)
                d[x] = (byte)(((66 * s[2] + 129 * s[1] + 25 * s[0] + 128) >> 8) + 16);
        }
        int cw = w / 2;
        for (int row = 0; row < h / 2; row++)
        {
            byte* r0 = src + 2 * row * stride;
            byte* r1 = r0 + stride;
            for (int x = 0; x < cw; x++)
            {
                byte* a = r0 + 8 * x;
                byte* b = r1 + 8 * x;
                int bl = a[0] + a[4] + b[0] + b[4], g = a[1] + a[5] + b[1] + b[5], r = a[2] + a[6] + b[2] + b[6];
                up[row * cw + x] = (byte)Math.Clamp((((-38 * r - 74 * g + 112 * bl) >> 2) + 32768 + 128) >> 8, 0, 255);
                vp[row * cw + x] = (byte)Math.Clamp((((112 * r - 94 * g - 18 * bl) >> 2) + 32768 + 128) >> 8, 0, 255);
            }
        }
    }

    public void Dispose()
    {
        if (_encoder == IntPtr.Zero) return;
        ((delegate* unmanaged<IntPtr, int>)_vtbl[Uninitialize])(_encoder);
        OpenH264.DestroyEncoder(_encoder);
        _encoder = IntPtr.Zero;
        NativeMemory.Free(_info);
        if (_outPin.IsAllocated) _outPin.Free();
    }
}
