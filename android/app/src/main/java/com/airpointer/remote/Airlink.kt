package com.airpointer.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
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
    @JvmStatic external fun networkChanged(node: Long)
}

/**
 * The phone's internet endpoint: started on first use, stopped when the user disconnects.
 *
 * Two things keep it working when the phone's network changes (Wi-Fi <-> mobile data, a new address):
 * Android's network changes are passed on to iroh, which can't see them from native code on Android, so
 * connections move over right away; and a connect that fails anyway gets a fresh endpoint, in case the old
 * one is stuck on a network that's gone.
 */
object Internet {
    private var node = 0L
    private var watcher: ConnectivityManager.NetworkCallback? = null
    private val notifier = Executors.newSingleThreadExecutor { Thread(it, "airlink-network") }

    @Synchronized
    private fun node(context: Context): Long {
        if (node == 0L) {
            if (!Airlink.available) throw IOException("Internet connections aren't available in this build")
            node = Airlink.start(context.applicationContext, secret(context))
            watchNetwork(context.applicationContext)
        }
        return node
    }

    /** Tells the endpoint whenever the phone's default network changes or gets a new address. */
    private fun watchNetwork(app: Context) {
        if (watcher != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
        }
        if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess) watcher = callback
    }

    private fun changed() {
        val n = synchronized(this) { node }
        if (n != 0L) notifier.execute { Airlink.networkChanged(n) }
    }

    /** Drops [old] (if it's still the current endpoint), so the next connect starts a fresh one. */
    @Synchronized
    private fun discard(old: Long) {
        if (node != old) return
        node = 0L
        thread(name = "airlink-stop") { Airlink.stop(old) }
    }

    /** The endpoint's key (only for the encrypted QUIC link; who-is-who is proven by DeviceKey on top). */
    private fun secret(context: Context): ByteArray {
        val sp = context.getSharedPreferences("internet", Context.MODE_PRIVATE)
        sp.getString("secret", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("secret", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        return key
    }

    /**
     * Blocking: connects to [pc] over the internet (by its id; no address needed). If the first try fails,
     * tries once more with a fresh endpoint within the same time.
     */
    fun open(context: Context, pc: PairedPc, timeoutMs: Int = 20000): AirlinkPipe {
        val id = pc.internetId ?: throw IOException("${pc.name} hasn't enabled internet access yet")
        val app = context.applicationContext
        val start = SystemClock.elapsedRealtime()
        val first = node(app)
        try {
            return AirlinkPipe(Airlink.connect(first, id, timeoutMs * 3 / 5))
        } catch (e: IOException) {
            discard(first)
            val left = timeoutMs - (SystemClock.elapsedRealtime() - start).toInt()
            if (left < 3000) throw e
            return AirlinkPipe(Airlink.connect(node(app), id, left))
        }
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
