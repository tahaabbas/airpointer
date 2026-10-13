using System.Buffers.Text;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace AirPointer.Core;

/// <summary>
/// This computer's key pair (ECDSA P-256), in a self-signed certificate used for TLS and for signing
/// authentication challenges. Created on first run in %APPDATA%\AirPointer\identity.pfx and kept forever:
/// phones pin it when they pair, so a new key means pairing every phone again.
/// </summary>
public sealed class Identity : IDisposable
{
    public X509Certificate2 Certificate { get; }

    /// <summary>SubjectPublicKeyInfo (DER) of this computer's key: what phones store when pairing.</summary>
    public byte[] PublicKey { get; }

    /// <summary>SHA-256 of <see cref="PublicKey"/>: identifies this computer (in the QR code, discovery, auth).</summary>
    public byte[] Id { get; }

    public string IdText => Base64Url.EncodeToString(Id);

    private readonly ECDsa _key;

    private Identity(X509Certificate2 cert)
    {
        Certificate = cert;
        _key = cert.GetECDsaPrivateKey() ?? throw new InvalidOperationException("Identity certificate has no private key.");
        PublicKey = cert.PublicKey.ExportSubjectPublicKeyInfo();
        Id = SHA256.HashData(PublicKey);
    }

    private static string FilePath => Path.Combine(AppConfig.Dir, "identity.pfx");

    public static Identity LoadOrCreate()
    {
        if (File.Exists(FilePath))
        {
            try
            {
                return new Identity(X509CertificateLoader.LoadPkcs12FromFile(FilePath, null));
            }
            catch (CryptographicException)
            {
                // Unreadable: make a new one (paired phones will have to pair again).
                File.Move(FilePath, FilePath + ".broken", overwrite: true);
            }
        }

        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest($"CN=AirPointer {Environment.MachineName}", key, HashAlgorithmName.SHA256);
        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(100));
        var pfx = created.Export(X509ContentType.Pfx);
        Directory.CreateDirectory(AppConfig.Dir);
        File.WriteAllBytes(FilePath, pfx);
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(FilePath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        // Reload from the PFX: on Windows, TLS (SChannel) can't use the in-memory key of a fresh certificate.
        return new Identity(X509CertificateLoader.LoadPkcs12(pfx, null));
    }

    public byte[] Sign(byte[] data) => _key.SignData(data, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    public void Dispose()
    {
        _key.Dispose();
        Certificate.Dispose();
    }
}
