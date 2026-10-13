package com.airpointer.remote

import android.content.Context
import android.util.Base64
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * JNI binding of native/airlink (Rust + iroh): peer-to-peer QUIC to the computer over the internet,
 * direct through NAT when possible, else via a public relay. See native/airlink/src/jni_api.rs.
 */
object Airlink {
    /** False if the native library isn't in this build/ABI: internet connections are then unavailable. */
    val available: Boolean = runCatching { System.loadLibrary("airlink") }.isSuccess

    /** [context]: the application context (iroh's networking code needs it on Android). */
    @JvmStatic external fun start(context: Context, secret: ByteArray): Long
    @JvmStatic external fun id(node: Long): ByteArray?
    @JvmStatic external fun connect(node: Long, peer: ByteArray, timeoutMs: Int): Long
    @JvmStatic external fun read(pipe: Long, buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int
    @JvmStatic external fun write(pipe: Long, buf: ByteArray, off: Int, len: Int, timeoutMs: Int)
    @JvmStatic external fun direct(pipe: Long): Int
    @JvmStatic external fun close(pipe: Long)
    @JvmStatic external fun stop(node: Long)
}

/** The phone's internet endpoint: started on first use, stopped when the user disconnects. */
object Internet {
    private var node = 0L

    @Synchronized
    private fun node(context: Context): Long {
        if (node == 0L) {
            if (!Airlink.available) throw IOException("Internet connections aren't available in this build")
            node = Airlink.start(context.applicationContext, secret(context))
        }
        return node
    }

    /** The endpoint's key (only for the encrypted QUIC link; who-is-who is proven by DeviceKey on top). */
    private fun secret(context: Context): ByteArray {
        val sp = context.getSharedPreferences("internet", Context.MODE_PRIVATE)
        sp.getString("secret", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("secret", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        return key
    }

    /** Blocking: connects to [pc] over the internet (by its id; no address needed). */
    fun open(context: Context, pc: PairedPc, timeoutMs: Int = 20000): AirlinkPipe {
        val id = pc.internetId ?: throw IOException("${pc.name} hasn't enabled internet access yet")
        return AirlinkPipe(Airlink.connect(node(context.applicationContext), id, timeoutMs))
    }

    fun stop() {
        val n = synchronized(this) { node.also { node = 0L } }
        if (n != 0L) thread(name = "airlink-stop") { Airlink.stop(n) }
    }
}

/** One iroh stream as Java streams. */
class AirlinkPipe(private val pipe: Long) : Closeable {
    @Volatile var readTimeoutMs = 15000
    @Volatile private var closed = false

    /** True once a direct (hole-punched) path is in use; false while only relayed. */
    val direct: Boolean get() = !closed && Airlink.direct(pipe) == 1

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (closed) throw IOException("Closed")
            return Airlink.read(pipe, b, off, len, readTimeoutMs)
        }

        override fun close() = this@AirlinkPipe.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Closed")
            if (len > 0) Airlink.write(pipe, b, off, len, 10000)
        }

        override fun close() = this@AirlinkPipe.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        Airlink.close(pipe)
    }
}
