using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace AirPointer.Core;

/// <summary>
/// Bluetooth (RFCOMM / "serial port profile") listener for Windows, built directly on Winsock so no
/// extra libraries are needed. The service is published in SDP under <see cref="Protocol.BluetoothServiceId"/>
/// so the phone can find it by UUID. The phone must be paired with the PC first.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class BluetoothRfcommServer(ServerHost host) : IDisposable
{
    private const int AF_BTH = 32;
    private const int BTHPROTO_RFCOMM = 3;
    private const int NS_BTH = 16;
    private const int RNRSERVICE_REGISTER = 0;
    private const int RNRSERVICE_DELETE = 2;
    private const int SockaddrBthSize = 30;

    private Socket? _listener;
    private IntPtr _sockaddr, _guid, _csAddr, _name;
    private bool _registered;

    /// <summary>SOCKADDR_BTH (packed): u16 family, u64 btAddr, GUID serviceClassId, u32 port.</summary>
    private sealed class BthEndPoint(ulong address, uint port) : EndPoint
    {
        public ulong Address { get; } = address;
        public uint Port { get; } = port;

        public override AddressFamily AddressFamily => (AddressFamily)AF_BTH;

        public override SocketAddress Serialize()
        {
            var sa = new SocketAddress((AddressFamily)AF_BTH, SockaddrBthSize);
            for (int i = 0; i < 8; i++) sa[2 + i] = (byte)(Address >> (8 * i));
            for (int i = 0; i < 16; i++) sa[10 + i] = 0;
            for (int i = 0; i < 4; i++) sa[26 + i] = (byte)(Port >> (8 * i));
            return sa;
        }

        public override EndPoint Create(SocketAddress sa)
        {
            ulong addr = 0;
            uint port = 0;
            for (int i = 0; i < 8; i++) addr |= (ulong)sa[2 + i] << (8 * i);
            for (int i = 0; i < 4; i++) port |= (uint)sa[26 + i] << (8 * i);
            return new BthEndPoint(addr, port);
        }

        public override string ToString() => Address.ToString("X12");
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SOCKET_ADDRESS
    {
        public IntPtr lpSockaddr;
        public int iSockaddrLength;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CSADDR_INFO
    {
        public SOCKET_ADDRESS LocalAddr;
        public SOCKET_ADDRESS RemoteAddr;
        public int iSocketType;
        public int iProtocol;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WSAQUERYSET
    {
        public int dwSize;
        public IntPtr lpszServiceInstanceName;
        public IntPtr lpServiceClassId;
        public IntPtr lpVersion;
        public IntPtr lpszComment;
        public int dwNameSpace;
        public IntPtr lpNSProviderId;
        public IntPtr lpszContext;
        public int dwNumberOfProtocols;
        public IntPtr lpafpProtocols;
        public IntPtr lpszQueryString;
        public int dwNumberOfCsAddrs;
        public IntPtr lpcsaBuffer;
        public int dwOutputFlags;
        public IntPtr lpBlob;
    }

    [DllImport("ws2_32.dll", EntryPoint = "WSASetServiceW", SetLastError = true)]
    private static extern int WSASetService(ref WSAQUERYSET qs, int operation, int controlFlags);

    [DllImport("ws2_32.dll")]
    private static extern int WSAGetLastError();

    /// <summary>Starts listening and returns the RFCOMM channel number.</summary>
    public int Start()
    {
        Socket socket;
        try
        {
            socket = new Socket((AddressFamily)AF_BTH, SocketType.Stream, (ProtocolType)BTHPROTO_RFCOMM);
        }
        catch (SocketException)
        {
            throw new InvalidOperationException("no Bluetooth adapter / Bluetooth is turned off");
        }

        try
        {
            socket.Bind(new BthEndPoint(0, 0xFFFFFFFF)); // BT_PORT_ANY
            socket.Listen(2);
            var local = (BthEndPoint)socket.LocalEndPoint!;
            RegisterSdp(local.Port);
            _listener = socket;
            new Thread(AcceptLoop) { IsBackground = true, Name = "bt-accept" }.Start();
            return (int)local.Port;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    private void RegisterSdp(uint channel)
    {
        _sockaddr = Marshal.AllocHGlobal(SockaddrBthSize);
        var bytes = new byte[SockaddrBthSize];
        bytes[0] = AF_BTH;
        for (int i = 0; i < 4; i++) bytes[26 + i] = (byte)(channel >> (8 * i));
        Marshal.Copy(bytes, 0, _sockaddr, bytes.Length);

        _guid = Marshal.AllocHGlobal(16);
        Marshal.Copy(Protocol.BluetoothServiceId.ToByteArray(), 0, _guid, 16);

        var cs = new CSADDR_INFO
        {
            LocalAddr = new SOCKET_ADDRESS { lpSockaddr = _sockaddr, iSockaddrLength = SockaddrBthSize },
            RemoteAddr = new SOCKET_ADDRESS { lpSockaddr = _sockaddr, iSockaddrLength = SockaddrBthSize },
            iSocketType = (int)SocketType.Stream,
            iProtocol = BTHPROTO_RFCOMM,
        };
        _csAddr = Marshal.AllocHGlobal(Marshal.SizeOf<CSADDR_INFO>());
        Marshal.StructureToPtr(cs, _csAddr, false);

        _name = Marshal.StringToHGlobalUni("AirPointer");

        var qs = Query();
        if (WSASetService(ref qs, RNRSERVICE_REGISTER, 0) != 0)
            throw new InvalidOperationException($"could not publish the Bluetooth service (WSA error {WSAGetLastError()})");
        _registered = true;
    }

    private WSAQUERYSET Query() => new()
    {
        dwSize = Marshal.SizeOf<WSAQUERYSET>(),
        lpszServiceInstanceName = _name,
        lpServiceClassId = _guid,
        dwNameSpace = NS_BTH,
        dwNumberOfCsAddrs = 1,
        lpcsaBuffer = _csAddr,
    };

    private void AcceptLoop()
    {
        while (true)
        {
            Socket client;
            try
            {
                client = _listener!.Accept();
            }
            catch
            {
                return;
            }
            new Thread(() =>
            {
                using (client)
                {
                    client.ReceiveTimeout = 20000;
                    var remote = client.RemoteEndPoint?.ToString() ?? "bluetooth";
                    new Session(new NetworkStream(client, ownsSocket: false), "Bluetooth", remote, host).Run();
                }
            }) { IsBackground = true, Name = "bt-session" }.Start();
        }
    }

    public void Dispose()
    {
        if (_registered)
        {
            var qs = Query();
            WSASetService(ref qs, RNRSERVICE_DELETE, 0);
            _registered = false;
        }
        try { _listener?.Dispose(); } catch { /* ignore */ }
        foreach (var p in new[] { _sockaddr, _guid, _csAddr, _name })
            if (p != IntPtr.Zero) Marshal.FreeHGlobal(p);
        _sockaddr = _guid = _csAddr = _name = IntPtr.Zero;
    }
}
