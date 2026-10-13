using AirPointer.Core;

namespace AirPointer.Media;

/// <summary>An H.264 encoder for screen view: BGRA in, Annex-B (SPS/PPS in front of keyframes) out.</summary>
public interface IVideoEncoder : IDisposable
{
    /// <summary>
    /// Encodes one frame. <paramref name="data"/>/<paramref name="length"/> point at the encoded bytes (valid until
    /// the next call; length 0 = nothing to send this time). Throws if the encoder failed.
    /// </summary>
    void Encode(IntPtr bgra, int stride, bool keyframe, out IntPtr data, out int length, out bool isKeyframe);
}

public static class VideoEncoders
{
    /// <summary>Testing aid: use OpenH264 on Windows too, instead of Media Foundation.</summary>
    private static bool ForceOpenH264 => Environment.GetEnvironmentVariable("AIRPOINTER_FORCE_OPENH264") == "1";

    /// <summary>True if this computer can send screen view as H.264 right now.</summary>
    public static bool Available(AppConfig config) =>
        (OperatingSystem.IsWindows() && !ForceOpenH264) || (config.UseOpenH264 && OpenH264.IsDownloaded);

    /// <summary>
    /// Windows: Media Foundation's encoder (ships with Windows). Linux / macOS: Cisco's OpenH264, if the user
    /// turned it on. Width/height must be even. Throws if no encoder can be created.
    /// </summary>
    public static IVideoEncoder Create(AppConfig config, int width, int height, int fps, uint bitrate)
    {
        if (OperatingSystem.IsWindows() && !ForceOpenH264) return new MediaFoundationEncoder(width, height, fps, bitrate);
        if (config.UseOpenH264) return new OpenH264Encoder(width, height, fps, bitrate);
        throw new InvalidOperationException("No H.264 encoder: turn on OpenH264 in the AirPointer window.");
    }
}

/// <summary>Media Foundation H.264 encoder, in native/airlink (video_win.rs).</summary>
internal sealed class MediaFoundationEncoder : IVideoEncoder
{
    private IntPtr _encoder;

    public MediaFoundationEncoder(int width, int height, int fps, uint bitrate)
    {
        _encoder = Airlink.VideoNew((uint)width, (uint)height, (uint)fps, bitrate);
        if (_encoder == IntPtr.Zero) throw new InvalidOperationException(Airlink.LastErrorMessage());
    }

    public void Encode(IntPtr bgra, int stride, bool keyframe, out IntPtr data, out int length, out bool isKeyframe)
    {
        if (Airlink.VideoEncode(_encoder, bgra, (nuint)stride, keyframe ? 1 : 0, out data, out var len, out int key) != 0)
            throw new InvalidOperationException(Airlink.LastErrorMessage());
        length = (int)len;
        isKeyframe = key != 0;
    }

    public void Dispose()
    {
        if (_encoder != IntPtr.Zero) Airlink.VideoFree(_encoder);
        _encoder = IntPtr.Zero;
    }
}
