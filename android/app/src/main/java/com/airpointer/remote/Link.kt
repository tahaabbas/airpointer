package com.airpointer.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * The single active connection, shared between activities and the foreground service.
 *
 * Once connected, the link stays up until the user disconnects: if the network drops, it keeps
 * reconnecting in the background (state RECONNECTING) instead of giving up.
 */
object Link {
    enum class State { DISCONNECTED, CONNECTED, RECONNECTING }

    /** Knows how to (re)open a connection. [connect] blocks and throws on failure. */
    interface Target {
        val name: String
        fun connect(): MouseSink
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(State, String?) -> Unit>()
    private var appContext: Context? = null
    private var target: Target? = null
    @Volatile private var generation = 0

    @Volatile var sink: MouseSink? = null
        private set
    var state = State.DISCONNECTED
        private set

    /** Why the last link ended by itself (e.g. this phone was removed on the computer); the connect screen shows it once. */
    var lastError: String? = null

    /** Name of the computer we're connected (or reconnecting) to. */
    val name: String get() = sink?.label ?: target?.name ?: ""

    /** Whose settings (shortcut buttons) apply: one per paired computer. */
    data class Profile(val key: String, val name: String, val os: String?) : java.io.Serializable {
        companion object {
            fun of(pc: PairedPc) = Profile("pc:${pc.idText}", pc.name, pc.os)
            val GENERIC = Profile("default", "Other connections", null)
        }
    }

    /** The current connection's profile. */
    val profile: Profile get() = (target as? PcTarget)?.pc?.let { Profile.of(it) } ?: Profile.GENERIC

    /** The paired computer, when connected to the PC app over Wi-Fi or the internet (screen view and sound need it). */
    val pcTarget: PcTarget? get() = target as? PcTarget

    /** Listeners are called on the main thread with the new state and an optional message. */
    fun addListener(l: (State, String?) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State, String?) -> Unit) { listeners.remove(l) }

    /** Main thread: a fresh connection was made by the user. */
    fun start(context: Context, t: Target, s: MouseSink) {
        stopInternal()
        appContext = context.applicationContext
        target = t
        sink = s
        setState(State.CONNECTED, null)
        ConnectionService.start(context.applicationContext)
    }

    /** Main thread: the user chose to disconnect. This is the only way a working link is closed. */
    fun disconnect() {
        if (state == State.DISCONNECTED && sink == null) return
        stopInternal()
        Internet.stop() // no need to stay reachable for the relay any more
        setState(State.DISCONNECTED, null)
    }

    private fun stopInternal() {
        generation++
        RemoteMedia.reset()
        target = null
        val s = sink
        sink = null
        s?.close()
        appContext?.let { ConnectionService.stop(it) }
    }

    /** Any thread: a sink reports that its connection died. */
    fun lost(s: MouseSink, reason: String) {
        main.post {
            if (sink !== s) return@post
            sink = null
            val t = target
            if (t == null) {
                setState(State.DISCONNECTED, reason)
                return@post
            }
            setState(State.RECONNECTING, reason)
            reconnect(t, generation)
        }
    }

    private fun reconnect(t: Target, gen: Int) {
        thread(name = "airpointer-reconnect") {
            var attempt = 0
            while (gen == generation) {
                try {
                    Thread.sleep(if (attempt == 0) 300L else min(5000L, 1000L * attempt))
                } catch (e: InterruptedException) {
                    return@thread
                }
                if (gen != generation) return@thread
                try {
                    val s = t.connect()
                    main.post {
                        if (gen == generation && target === t) {
                            sink = s
                            setState(State.CONNECTED, null)
                        } else {
                            s.close()
                        }
                    }
                    return@thread
                } catch (e: NetSink.HandshakeException) {
                    if (e.fatal) {
                        // Phone removed on the computer or incompatible versions: retrying can't help.
                        main.post {
                            if (gen == generation) {
                                stopInternal()
                                Internet.stop()
                                setState(State.DISCONNECTED, e.message)
                            }
                        }
                        return@thread
                    }
                    attempt++
                } catch (e: Exception) {
                    attempt++
                }
            }
        }
    }

    private fun setState(st: State, message: String?) {
        state = st
        if (st == State.DISCONNECTED) lastError = message
        for (l in listeners.toList()) l(st, message)
    }

    fun deviceName(): String = Build.MODEL ?: "Android"
}

/**
 * A computer paired by QR code. Connects on the local network when possible (its known addresses, then a
 * discovery scan in case its address changed), otherwise over the internet (iroh) if the computer allows it.
 */
class PcTarget(context: Context, pc: PairedPc) : Link.Target {
    private val app = context.applicationContext

    @Volatile var pc: PairedPc = pc
        private set

    /** How the last successful connection went; screen view and sound take the same route. */
    @Volatile var viaInternet = false
        private set

    val host: String get() = pc.hosts.first()
    val port: Int get() = pc.port
    override val name: String get() = pc.name

    override fun connect(): MouseSink {
        val local = try {
            connectLocal()
        } catch (e: NetSink.HandshakeException) {
            throw e
        } catch (e: Exception) {
            if (pc.internetId == null || !Airlink.available) throw e
            null
        }
        val sink = local ?: NetSink.connectInternet(app, pc)
        viaInternet = local == null
        sink.pc?.let { pc = it } // may carry a newly learned internet id
        sink.host?.let { remember(it, pc.port) }
        return sink
    }

    private fun connectLocal(): NetSink = try {
        NetSink.connectTcp(app, pc.hosts, pc.port, only = pc)
    } catch (e: NetSink.HandshakeException) {
        throw e
    } catch (e: Exception) {
        // Not at its known addresses (e.g. the router gave it a new one): find it on the network by its key.
        val found = Discovery.discover(1200).firstOrNull { it.id?.contentEquals(pc.id) == true } ?: throw e
        NetSink.connectTcp(app, listOf(found.host), found.port, only = pc)
    }

    /** Blocking: a connection for screen view / sound, along the same route as the control link. */
    fun openChannel(screen: Boolean): Channel {
        if (viaInternet) return Channel.internet(Internet.open(app, pc))
        val c = Secure.connectTls(host, port, 4000) { it.contentEquals(pc.id) }
        if (screen) c.socket.receiveBufferSize = 1 shl 20
        return Channel.tls(c)
    }

    /** Puts a working address first (and saves it for next time). */
    fun remember(host: String, port: Int) {
        if (host == pc.hosts.firstOrNull() && port == pc.port) return
        pc = pc.withHost(host, port)
        PcStore.save(app, pc)
    }
}

class BluetoothTarget(context: Context, private val device: BluetoothDevice, override val name: String) : Link.Target {
    private val app = context.applicationContext
    override fun connect(): MouseSink = NetSink.connectBluetooth(app, device)
}

@SuppressLint("NewApi")
class HidTarget(private val context: Context, private val device: BluetoothDevice, override val name: String) : Link.Target {
    override fun connect(): MouseSink {
        if (!HidManager.connectBlocking(context, device, 10_000)) throw IOException("Bluetooth host not reachable")
        return HidSink(name)
    }
}
