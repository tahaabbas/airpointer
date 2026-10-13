using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace AirPointer.Core;

/// <summary>Accepts phones connecting over Wi-Fi / LAN. Every connection is TLS with this computer's certificate.</summary>
internal sealed class TcpServer(ServerHost host, int port) : IDisposable
{
    private TcpListener? _listener;

    public void Start()
    {
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        new Thread(AcceptLoop) { IsBackground = true, Name = "tcp-accept" }.Start();
    }

    private void AcceptLoop()
    {
        while (true)
        {
            TcpClient client;
            try
            {
                client = _listener!.AcceptTcpClient();
            }
            catch
            {
                return; // listener stopped
            }
            new Thread(() => Handle(client)) { IsBackground = true, Name = "tcp-session" }.Start();
        }
    }

    private void Handle(TcpClient client)
    {
        using (client)
        {
            client.NoDelay = true;
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            // The phone pings every 2 s; if nothing arrives for 20 s the link is dead (the phone reconnects).
            client.ReceiveTimeout = 20000;
            var remote = (client.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
            var first = new byte[1];
            try
            {
                if (client.Client.Receive(first, SocketFlags.Peek) != 1) return;
            }
            catch (SocketException)
            {
                return;
            }
            if (first[0] != 0x16) // not a TLS handshake: an old phone app speaking plain frames
            {
                RefuseOldPhone(client.GetStream());
                return;
            }

            var tls = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
            try
            {
                tls.AuthenticateAsServer(new SslServerAuthenticationOptions
                {
                    ServerCertificate = host.Identity.Certificate,
                    ClientCertificateRequired = false, // the phone proves itself with its key right after (Auth.cs)
                    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                });
            }
            catch (Exception e) when (e is AuthenticationException or IOException)
            {
                tls.Dispose();
                return;
            }
            new Session(tls, "Wi-Fi", remote, host).Run();
        }
    }

    /// <summary>Answers a pre-pairing phone app (PIN, no TLS) so it says "versions don't match - update both apps".</summary>
    private static void RefuseOldPhone(NetworkStream stream)
    {
        try
        {
            if (new FrameReader(stream).Read() is { Type: Protocol.Hello or Protocol.MediaHello })
                Session.WriteWelcome(stream, Protocol.StatusBadVersion);
        }
        catch (Exception e) when (e is IOException or SocketException)
        {
            // gone
        }
    }

    public void Dispose()
    {
        try { _listener?.Stop(); } catch { /* ignore */ }
    }
}

/// <summary>Answers the phone's UDP broadcast so it can list this computer automatically.</summary>
internal sealed class DiscoveryResponder(ServerHost host) : IDisposable
{
    private UdpClient? _udp;

    public void Start()
    {
        _udp = new UdpClient();
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, Protocol.DiscoveryPort));
        new Thread(Loop) { IsBackground = true, Name = "discovery" }.Start();
    }

    private void Loop()
    {
        while (true)
        {
            try
            {
                var from = new IPEndPoint(IPAddress.Any, 0);
                var data = _udp!.Receive(ref from);
                if (Encoding.ASCII.GetString(data) != Protocol.DiscoveryRequest) continue;
                var name = Protocol.ComputerName.Replace('|', '_');
                var reply = Encoding.UTF8.GetBytes(
                    $"{Protocol.DiscoveryReplyPrefix}|{Protocol.Version}|{name}|{host.Config.ListenPort}|{Protocol.OsName}|1|{host.Identity.IdText}");
                _udp.Send(reply, reply.Length, from);
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                // e.g. ICMP port unreachable from a previous reply; keep going.
                if (_udp?.Client == null) return;
            }
        }
    }

    public void Dispose()
    {
        try { _udp?.Dispose(); } catch { /* ignore */ }
    }
}
