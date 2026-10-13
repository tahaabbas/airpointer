package com.airpointer.remote

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Sets up "Bluetooth mouse mode": the phone registers as a Bluetooth HID mouse/keyboard. */
@TargetApi(Build.VERSION_CODES.P)
@SuppressLint("MissingPermission")
class HidActivity : Activity(), HidManager.Listener {

    private lateinit var statusText: TextView
    private lateinit var pairedList: LinearLayout
    private lateinit var btnOpenRemote: Button
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hid)
        statusText = findViewById(R.id.hidStatus)
        pairedList = findViewById(R.id.pairedList)
        btnOpenRemote = findViewById(R.id.btnOpenRemote)

        findViewById<Button>(R.id.btnDiscoverable).setOnClickListener { makeDiscoverable() }
        btnOpenRemote.setOnClickListener { openRemote() }
        findViewById<Button>(R.id.btnStopHid).setOnClickListener {
            Link.disconnect()
            HidManager.stop()
            finish()
        }

        HidManager.listener = this
        if (hasPermissions()) startHid() else requestPermissions(requiredPermissions(), REQ)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        HidManager.listener = this
        render(HidManager.state, HidManager.host, null)
        if (hasPermissions()) refreshPaired()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (HidManager.listener === this) HidManager.listener = null
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    private fun hasPermissions() =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ) return
        if (hasPermissions()) {
            startHid()
            refreshPaired()
        } else {
            statusText.text = "Bluetooth permissions are required for mouse mode."
        }
    }

    private fun startHid() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            statusText.text = "This phone has no Bluetooth."
            return
        }
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)
            return
        }
        HidManager.start(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE && resultCode == RESULT_OK) {
            HidManager.start(this)
            refreshPaired()
        }
    }

    private fun makeDiscoverable() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), REQ)
            return
        }
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
    }

    private fun refreshPaired() {
        pairedList.removeAllViews()
        val adapter = bluetoothAdapter() ?: return
        val devices = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
            .sortedBy { if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER) 0 else 1 }
        if (devices.isEmpty()) {
            pairedList.addView(TextView(this).apply {
                text = "No paired devices yet."
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }
        val density = resources.displayMetrics.density
        for (d in devices) {
            val b = Button(this, null, 0, R.style.Btn)
            val isComputer = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            b.text = Ui.iconText(this, if (isComputer) R.drawable.lic_monitor else R.drawable.lic_smartphone, d.name ?: d.address, getColor(R.color.text))
            b.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * density).toInt() }
            b.setOnClickListener { connectTo(d) }
            pairedList.addView(b)
        }
    }

    private fun connectTo(d: BluetoothDevice) {
        if (HidManager.state == HidManager.State.OFF) {
            startHid()
            statusText.text = "Starting Bluetooth mouse mode… tap the computer again in a moment."
            return
        }
        HidManager.connect(d)
    }

    override fun onHidState(state: HidManager.State, device: BluetoothDevice?, message: String?) {
        render(state, device, message)
        if (state == HidManager.State.CONNECTED && device != null) {
            // While reconnecting, Link's own retry loop picks this connection up.
            if (Link.state == Link.State.DISCONNECTED) startLink(device)
            if (resumed) openRemote()
        }
    }

    private fun render(state: HidManager.State, device: BluetoothDevice?, message: String?) {
        val name = device?.name ?: device?.address
        val text = when (state) {
            HidManager.State.OFF -> "Bluetooth mouse mode is off."
            HidManager.State.STARTING -> "Starting…"
            HidManager.State.READY -> "Ready. Pair from your computer, or tap an already-paired computer below."
            HidManager.State.CONNECTING -> "Connecting to ${name ?: "computer"}…"
            HidManager.State.CONNECTED -> "Connected to $name as a Bluetooth mouse."
            HidManager.State.UNSUPPORTED -> "Not supported on this phone."
        }
        statusText.text = if (message != null) "$text\n$message" else text
        statusText.setTextColor(getColor(if (state == HidManager.State.CONNECTED) R.color.ok else R.color.text_muted))
        btnOpenRemote.isEnabled = state == HidManager.State.CONNECTED
    }

    private fun startLink(device: BluetoothDevice) {
        val label = "${device.name ?: device.address} (Bluetooth mouse)"
        Link.start(this, HidTarget(applicationContext, device, label), HidSink(label))
    }

    private fun openRemote() {
        if (Link.state == Link.State.DISCONNECTED) HidManager.host?.let { startLink(it) }
        if (Link.state != Link.State.DISCONNECTED) startActivity(Intent(this, RemoteActivity::class.java))
    }

    companion object {
        private const val REQ = 2
        private const val REQ_ENABLE = 3
    }
}
