package com.airpointer.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Talks to the AirPointer desktop app over a TCP (Wi-Fi) or RFCOMM (Bluetooth) stream. */
class NetSink private constructor(
    private val serverName: String,
    private val connection: Closeable,
    private val input: InputStream,
    output: OutputStream,
    /** Set when connected over the internet. */
    private val internet: AirlinkPipe?,
) : QueuedSink("airpointer-writer") {

    /** e.g. "PC (Wi-Fi)", "PC (Internet)", "PC (Internet, relayed)"; relayed can turn direct once hole punching succeeds. */
    override val label: String
        get() = when {
            internet == null -> if (serverName.endsWith(")")) serverName else "$serverName (Wi-Fi)"
            internet.direct -> "$serverName (Internet)"
            else -> "$serverName (Internet, relayed)"
        }

    val viaInternet: Boolean get() = internet != null

    private val out = BufferedOutputStream(output, 1024)

    override val keepAliveMs = 2000L // ping interval; the computer answers each ping

    /** [fatal] = retrying can't succeed (not paired / removed, incompatible versions). */
    class HandshakeException(message: String, val fatal: Boolean = false) : IOException(message)

    /** The computer this sink is connected to, and over Wi-Fi the address that worked. */
    var pc: PairedPc? = null
        private set
    var host: String? = null
        private set

    companion object {
        /**
         * Blocking: TLS to [hosts] (all tried at once) trusting only paired computers ([only] = just that one),
         * then the signed challenge/response.
         */
        fun connectTcp(context: Context, hosts: List<String>, port: Int, only: PairedPc? = null): NetSink {
            val c = Secure.connectFirst(hosts, port, { id ->
                if (only != null) id.contentEquals(only.id) else PcStore.find(context, id) != null
            })
            return open(context, Channel.tls(c))
        }

        /** Blocking: over the internet (iroh), to a computer whose internet id this phone knows. */
        fun connectInternet(context: Context, pc: PairedPc): NetSink =
            open(context, Channel.internet(Internet.open(context, pc)))

        private fun open(context: Context, ch: Channel): NetSink {
            try {
                val nonce = Secure.nonce()
                val hello = Protocol.frame(Protocol.HELLO, Protocol.helloFields(Link.deviceName(), DeviceKey.id, nonce))
                val auth = Secure.authenticate(context, ch.input, ch.output, hello, nonce, ch.serverId)
                // Pings go out every 2 s and each gets a reply; 15 s of silence means the link is really dead
                // (it is then re-established automatically by Link).
                ch.setReadTimeout(15000)
                return NetSink(auth.serverName, ch, ch.input, ch.output, ch.internet).also {
                    it.pc = auth.pc
                    it.host = ch.host
                    it.start()
                }
            } catch (e: Exception) {
                ch.close()
                throw e
            }
        }

        /** Blocking: call from a background thread. The PC must be paired in Android Bluetooth settings and by QR code. */
        @SuppressLint("MissingPermission")
        fun connectBluetooth(context: Context, device: BluetoothDevice): NetSink {
            val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(Protocol.BT_SERVICE_UUID)
            try {
                socket.connect()
                val nonce = Secure.nonce()
                val hello = Protocol.frame(Protocol.HELLO, Protocol.helloFields(Link.deviceName(), DeviceKey.id, nonce))
                val auth = Secure.authenticate(context, socket.inputStream, socket.outputStream, hello, nonce, null)
                return NetSink("${auth.serverName} (Bluetooth)", socket, socket.inputStream, socket.outputStream, null).also {
                    it.pc = auth.pc
                    it.start()
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }

    private fun start() {
        startWorker()
        Thread({ readLoop() }, "airpointer-reader").start()
    }

    private fun readLoop() {
        try {
            while (!closed) Protocol.readFrame(input) // only PONGs arrive; reading detects a dead link
        } catch (e: Exception) {
            if (!closed) fail("Connection lost")
        }
    }

    private fun fail(reason: String) {
        stopWorker()
        runCatching { connection.close() }
        Link.lost(this, reason)
    }

    override fun onWorkerFailed(e: Exception) = fail("Connection lost")

    override fun onKeepAlive() = out.write(Protocol.frame(Protocol.PING))

    override fun flush() = out.flush()

    override fun execute(op: Op) {
        val bytes = when (op) {
            is Op.Move -> moveFrames(op.dx, op.dy)
            is Op.Button -> Protocol.frame(Protocol.BUTTON, byteArrayOf(op.button.toByte(), op.action.toByte()))
            is Op.Scroll -> Protocol.frame(Protocol.SCROLL, Protocol.i16(op.v, op.h))
            is Op.Zoom -> Protocol.frame(Protocol.ZOOM, byteArrayOf(op.steps.coerceIn(-127, 127).toByte()))
            is Op.Key -> Protocol.frame(Protocol.KEY, byteArrayOf(op.key.toByte()))
            is Op.Text -> Protocol.frame(Protocol.TEXT, Protocol.str16(op.text))
            is Op.Action -> Protocol.frame(Protocol.ACTION, byteArrayOf(op.action.toByte()))
            is Op.Combo -> Protocol.frame(Protocol.COMBO, Protocol.str8(op.combo))
            is Op.ComboDown -> Protocol.frame(Protocol.COMBO_DOWN, Protocol.str8(op.combo))
            Op.ComboUp -> Protocol.frame(Protocol.COMBO_UP)
            Op.Stop -> return
        }
        out.write(bytes)
    }

    private fun moveFrames(dx: Int, dy: Int): ByteArray {
        // Split very large moves so each fits in an int16.
        var rx = dx
        var ry = dy
        val parts = ArrayList<ByteArray>()
        do {
            val sx = rx.coerceIn(-32000, 32000)
            val sy = ry.coerceIn(-32000, 32000)
            parts.add(Protocol.frame(Protocol.MOVE, Protocol.i16(sx, sy)))
            rx -= sx; ry -= sy
        } while (rx != 0 || ry != 0)
        return parts.reduce { a, b -> a + b }
    }

    override fun close() {
        if (closed) return
        stopWorker()
        // Give the writer a moment to send any pending button releases, then close.
        Thread {
            Thread.sleep(150)
            runCatching { connection.close() }
        }.start()
    }
}
