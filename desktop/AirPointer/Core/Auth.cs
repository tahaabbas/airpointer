using System.Security.Cryptography;
using System.Text;

namespace AirPointer.Core;

/// <summary>
/// Challenge/response after a Hello: the computer signs the phone's nonce, the phone signs the computer's,
/// each with its own private key. The phone checks the computer against the key it pinned when pairing;
/// the computer checks the phone against its list of paired phones. Works the same over TLS and Bluetooth.
/// </summary>
internal static class Auth
{
    public const int IdLength = 32, NonceLength = 32;

    /// <summary>What each side signs: a label (so a signature can't be replayed in the other role) + both nonces + the peer's id.</summary>
    public static byte[] Transcript(bool server, ReadOnlySpan<byte> nonceC, ReadOnlySpan<byte> nonceS, ReadOnlySpan<byte> peerId)
    {
        var label = Encoding.ASCII.GetBytes(server ? "AirPointer-v5-server" : "AirPointer-v5-client");
        return [.. label, .. nonceC, .. nonceS, .. peerId];
    }

    /// <summary>
    /// Proves who we are and checks the phone. Returns the paired phone, or null after telling the phone why not.
    /// </summary>
    public static PairedDevice? Authenticate(Stream stream, FrameReader reader, ServerHost host,
                                             byte[] deviceId, byte[] nonceC, string deviceName, string transport)
    {
        var device = host.FindDevice(deviceId);
        if (device == null)
        {
            Session.WriteWelcome(stream, Protocol.StatusNotPaired);
            host.Log($"Refused {deviceName} ({transport}): not paired. Pair it with the QR code first.");
            return null;
        }

        var nonceS = RandomNumberGenerator.GetBytes(NonceLength);
        var signature = host.Identity.Sign(Transcript(server: true, nonceC, nonceS, deviceId));
        FrameWriter.Write(stream, Protocol.AuthChallenge, [.. host.Identity.Id, .. nonceS, .. FrameWriter.Bytes8(signature)]);

        if (reader.Read() is not { Type: Protocol.AuthResponse } response) return null;
        var phoneSignature = new PayloadReader(response.Payload).Bytes8();
        bool valid;
        using (var key = ECDsa.Create())
        {
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(device.PublicKey), out _);
            valid = key.VerifyData(Transcript(server: false, nonceC, nonceS, host.Identity.Id), phoneSignature,
                                   HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        if (!valid)
        {
            Session.WriteWelcome(stream, Protocol.StatusAuthFailed);
            host.Log($"Refused {device.Name} ({transport}): its signature didn't verify.");
            return null;
        }
        host.MarkSeen(device);
        return device;
    }
}
