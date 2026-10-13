package com.airpointer.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class ServerInfo(
    val name: String,
    val host: String,
    val port: Int,
    val os: String,
    /** SHA-256 of the computer's key (matches [PairedPc.id]); null for PC apps older than pairing. */
    val id: ByteArray?,
)

/** Finds AirPointer desktop apps on the local network with a UDP broadcast. */
object Discovery {

    /** Blocking: call from a background thread. */
    fun discover(timeoutMs: Long = 1500): List<ServerInfo> {
        val found = LinkedHashMap<String, ServerInfo>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val request = Protocol.DISCOVERY_REQUEST.toByteArray(Charsets.US_ASCII)
            val targets = broadcastAddresses()
            repeat(2) {
                for (t in targets) {
                    runCatching { socket.send(DatagramPacket(request, request.size, t, Protocol.DISCOVERY_PORT)) }
                }
            }
            val buf = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val parts = text.split('|')
                if (parts.size < 5 || parts[0] != Protocol.DISCOVERY_REPLY_PREFIX) continue
                val host = packet.address.hostAddress ?: continue
                found[host] = ServerInfo(
                    name = parts[2],
                    host = host,
                    port = parts[3].toIntOrNull() ?: Protocol.DEFAULT_PORT,
                    os = parts[4],
                    id = parts.getOrNull(6)?.let { runCatching { Secure.unb64url(it) }.getOrNull() },
                )
            }
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { list.add(it) }
                }
            }
        }
        list.add(InetAddress.getByName("255.255.255.255"))
        return list.distinct()
    }
}
