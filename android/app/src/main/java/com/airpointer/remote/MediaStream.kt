package com.airpointer.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * A screen-view or sound stream from the PC app, on its own Wi-Fi connection next to the control link.
 * It runs on a background thread and keeps reconnecting (like [Link]) until [stop], unless the computer
 * refuses for good (not paired, feature off or unsupported there). Same security as the control link:
 * TLS to the paired computer's pinned key, then the signed challenge/response.
 */
class MediaStream(
    context: Context,
    private val target: PcTarget,
    private val kind: Int,
    private val listener: Listener,
    private val quality: Int = 0,
    private val fps: Int = 0,
) {
    interface Listener {
        /** Main thread. [message] = null once streaming; [fatal] = the stream gave up. */
        fun onMediaStatus(message: String?, fatal: Boolean)

        /** Stream thread. [data] is reused for the next frame; [backlog] = bytes already waiting behind it. */
        fun onMediaFrame(type: Int, data: ByteArray, length: Int, backlog: Int)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = true
    @Volatile private var channel: Channel? = null
    private val worker = thread(name = "airpointer-media-$kind") { loop() }

    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Any thread: sends a small frame to the computer on this connection (e.g. KEYFRAME_REQUEST). */
    fun send(frame: ByteArray) {
        val ch = channel ?: return
        runCatching {
            sender.execute {
                runCatching {
                    synchronized(ch) {
                        ch.output.write(frame)
                        ch.output.flush()
                    }
                }
            }
        }
    }

    fun stop() {
        sender.shutdown()
        running = false
        runCatching { channel?.close() }
        worker.interrupt()
    }

    private fun status(message: String?, fatal: Boolean = false) {
        main.post { if (running || fatal) listener.onMediaStatus(message, fatal) }
    }

    private fun loop() {
        var attempt = 0
        var buffer = ByteArray(256 * 1024)
        while (running) {
            var s: Channel? = null
            try {
                val ch = target.openChannel(screen = kind == Protocol.MEDIA_SCREEN)
                s = ch
                channel = ch
                ch.setReadTimeout(10_000) // the computer sends at least a keep-alive every second
                val nonce = Secure.nonce()
                val codecs = Protocol.CODEC_JPEG or
                    (if (VideoDecoder.supported(app, Protocol.CODEC_H264)) Protocol.CODEC_H264 else 0) or
                    (if (VideoDecoder.supported(app, Protocol.CODEC_H265)) Protocol.CODEC_H265 else 0)
                val hello = Protocol.frame(
                    Protocol.MEDIA_HELLO,
                    Protocol.helloFields(Link.deviceName(), DeviceKey.id, nonce) +
                        byteArrayOf(kind.toByte(), quality.toByte(), fps.toByte(), codecs.toByte()),
                )
                val input = DataInputStream(BufferedInputStream(ch.input, 64 * 1024))
                try {
                    Secure.authenticate(app, input, ch.output, hello, nonce, ch.serverId)
                } catch (e: NetSink.HandshakeException) {
                    if (!e.fatal) throw e
                    running = false
                    status(e.message, fatal = true)
                    return
                }
                status(null)
                attempt = 0
                while (running) {
                    val len = input.readInt()
                    if (len < 1 || len > 64 shl 20) throw IOException("bad media frame")
                    val type = input.readUnsignedByte()
                    val n = len - 1
                    if (buffer.size < n) buffer = ByteArray(n + n / 4)
                    input.readFully(buffer, 0, n)
                    listener.onMediaFrame(type, buffer, n, input.available())
                }
            } catch (e: Exception) {
                if (!running) break
                attempt++
                if (attempt == 2) status("Reconnecting…")
            } finally {
                runCatching { s?.close() }
            }
            try {
                Thread.sleep(min(5000L, 500L * attempt))
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}
