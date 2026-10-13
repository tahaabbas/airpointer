package com.airpointer.remote

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Makes the phone a standard Bluetooth mouse + keyboard (HID Device profile, Android 9+).
 * Works with any computer, TV or tablet without installing anything there.
 */
@SuppressLint("MissingPermission")
@TargetApi(Build.VERSION_CODES.P)
object HidManager {

    const val REPORT_MOUSE = 1
    const val REPORT_KEYBOARD = 2
    const val REPORT_CONSUMER = 3

    /** Composite descriptor: mouse (5 buttons, 16-bit X/Y, wheel, horizontal pan), keyboard, media keys. */
    private val DESCRIPTOR = byteArrayOf(
        // Mouse
        0x05, 0x01, 0x09, 0x02, 0xA1.b, 0x01, 0x85.b, REPORT_MOUSE.toByte(),
        0x09, 0x01, 0xA1.b, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01,
        0x95.b, 0x05, 0x75, 0x01, 0x81.b, 0x02,
        0x95.b, 0x01, 0x75, 0x03, 0x81.b, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x01, 0x80.b, 0x26, 0xFF.b, 0x7F,
        0x75, 0x10, 0x95.b, 0x02, 0x81.b, 0x06,
        0x09, 0x38, 0x15, 0x81.b, 0x25, 0x7F, 0x75, 0x08, 0x95.b, 0x01, 0x81.b, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81.b, 0x25, 0x7F, 0x75, 0x08, 0x95.b, 0x01, 0x81.b, 0x06,
        0xC0.b, 0xC0.b,
        // Keyboard
        0x05, 0x01, 0x09, 0x06, 0xA1.b, 0x01, 0x85.b, REPORT_KEYBOARD.toByte(),
        0x05, 0x07, 0x19, 0xE0.b, 0x29, 0xE7.b, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.b, 0x08, 0x81.b, 0x02,
        0x95.b, 0x01, 0x75, 0x08, 0x81.b, 0x01,
        0x95.b, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.b, 0x00,
        0xC0.b,
        // Consumer control (media / volume)
        0x05, 0x0C, 0x09, 0x01, 0xA1.b, 0x01, 0x85.b, REPORT_CONSUMER.toByte(),
        0x15, 0x00, 0x26, 0xFF.b, 0x03, 0x19, 0x00, 0x2A, 0xFF.b, 0x03, 0x75, 0x10, 0x95.b, 0x01, 0x81.b, 0x00,
        0xC0.b,
    )

    private val Int.b: Byte get() = toByte()

    enum class State { OFF, STARTING, READY, CONNECTING, CONNECTED, UNSUPPORTED }

    interface Listener {
        fun onHidState(state: State, device: BluetoothDevice?, message: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var hid: BluetoothHidDevice? = null
    private var registered = false

    @Volatile var host: BluetoothDevice? = null
        private set
    var state = State.OFF
        private set
    var listener: Listener? = null

    private fun set(newState: State, device: BluetoothDevice? = host, message: String? = null) {
        main.post {
            state = newState
            listener?.onHidState(newState, device, message)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidManager.registered = registered
            if (registered) {
                set(State.READY, null)
                // Reconnect automatically to the last host if the stack offers one.
                pluggedDevice?.let { connect(it) }
            } else if (state != State.OFF) {
                set(State.OFF, null, "Bluetooth mouse mode stopped")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    connectLatch?.countDown()
                    set(State.CONNECTED, device)
                }
                BluetoothProfile.STATE_CONNECTING -> set(State.CONNECTING, device)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    (Link.sink as? HidSink)?.let {
                        it.abandon()
                        Link.lost(it, "Bluetooth host disconnected")
                    }
                    if (registered) set(State.READY, null, "Disconnected from ${device.name ?: device.address}")
                }
            }
        }
    }

    fun start(context: Context) {
        if (state != State.OFF && state != State.UNSUPPORTED) {
            set(state)
            return
        }
        appContext = context.applicationContext
        val adapter = context.bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            set(State.OFF, null, "Turn on Bluetooth first")
            return
        }
        set(State.STARTING, null)
        val ok = adapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "AirPointer", "AirPointer phone mouse", "AirPointer",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR,
                )
                val accepted = hid!!.registerApp(sdp, null, null, executor, callback)
                if (!accepted) set(State.UNSUPPORTED, null, "This phone refused to act as a Bluetooth mouse")
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                host = null
                set(State.OFF, null)
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) set(State.UNSUPPORTED, null, "This phone does not support Bluetooth mouse mode (HID device profile)")
    }

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        set(State.CONNECTING, device)
        if (!h.connect(device)) set(State.READY, null, "Could not connect to ${device.name ?: device.address}")
    }

    @Volatile private var connectLatch: CountDownLatch? = null

    /**
     * Blocking (background thread): (re)connects to [device], restarting mouse mode if needed.
     * Used by [Link] to restore a dropped Bluetooth-mouse connection.
     */
    fun connectBlocking(context: Context, device: BluetoothDevice, timeoutMs: Long): Boolean {
        if (host == device) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        if (hid == null || !registered) {
            main.post { start(context) }
            while ((hid == null || !registered) && System.currentTimeMillis() < deadline) Thread.sleep(200)
            if (hid == null || !registered) return false
        }
        if (host == device) return true // the computer may reconnect on its own once registered
        val latch = CountDownLatch(1)
        connectLatch = latch
        main.post { connect(device) }
        latch.await(maxOf(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
        connectLatch = null
        return host == device
    }

    fun disconnect() {
        val h = hid ?: return
        host?.let { h.disconnect(it) }
    }

    fun stop() {
        val h = hid ?: run { set(State.OFF, null); return }
        host?.let { h.disconnect(it) }
        if (registered) h.unregisterApp()
        appContext?.bluetoothAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        hid = null
        registered = false
        host = null
        set(State.OFF, null)
    }

    fun sendReport(id: Int, data: ByteArray): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        return h.sendReport(d, id, data)
    }
}
