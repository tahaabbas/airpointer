package com.airpointer.remote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Connect screen: the computers this phone is paired with (tap to connect, long-press to forget),
 * unpaired AirPointer computers on this network (tap to pair), and connecting by IP address.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusBanner: View
    private lateinit var statusIcon: ImageView
    private lateinit var statusProgress: ProgressBar
    private lateinit var status: TextView
    private lateinit var hero: View
    private lateinit var computersSection: View
    private lateinit var pairedList: LinearLayout
    private lateinit var serverList: LinearLayout
    private lateinit var scanProgress: ProgressBar
    private lateinit var btnScan: ImageButton
    private lateinit var manualForm: View
    private lateinit var manualChevron: ImageView
    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var btnConnect: Button

    private var busy = false
    private var scanning = false
    /** What the last network scan found (paired computers on it are marked "On this network"). */
    private var nearby: List<ServerInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusBanner = findViewById(R.id.statusBanner)
        statusIcon = findViewById(R.id.statusIcon)
        statusProgress = findViewById(R.id.statusProgress)
        status = findViewById(R.id.status)
        hero = findViewById(R.id.hero)
        computersSection = findViewById(R.id.computersSection)
        pairedList = findViewById(R.id.pairedList)
        serverList = findViewById(R.id.serverList)
        scanProgress = findViewById(R.id.scanProgress)
        btnScan = findViewById(R.id.btnScan)
        manualForm = findViewById(R.id.manualForm)
        manualChevron = findViewById(R.id.manualChevron)
        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        btnConnect = findViewById(R.id.btnConnect)

        editHost.setText(prefs.lastHost)
        editPort.setText(prefs.lastPort.toString())

        findViewById<Button>(R.id.btnPairHero).apply {
            text = Ui.iconText(this@MainActivity, R.drawable.lic_scan_qr_code, "Scan pairing code", 0xFFFFFFFF.toInt(), 20f)
            setOnClickListener { scanQr() }
        }
        findViewById<TextView>(R.id.btnPair).apply {
            text = Ui.iconText(this@MainActivity, R.drawable.lic_plus, "Pair", getColor(R.color.accent_light), 16f)
            setOnClickListener { scanQr() }
        }
        addSteps(findViewById(R.id.heroSteps))
        btnScan.setOnClickListener { scan() }
        findViewById<View>(R.id.manualHeader).setOnClickListener { toggleManual() }
        btnConnect.setOnClickListener {
            val host = editHost.text.toString().trim()
            val port = editPort.text.toString().toIntOrNull() ?: Protocol.DEFAULT_PORT
            if (host.isEmpty()) {
                showError("Enter the computer's IP address.")
                return@setOnClickListener
            }
            connectAddress(host, port)
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.footer).text =
            "AirPointer ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""}"

        // Android 13+: the "Connected" notification (with its Disconnect button) needs permission.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        showComputers()
        if (!handlePairingLink(intent)) scan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // If a connection is still alive (e.g. user pressed Home), go straight back to the remote.
        if (Link.state != Link.State.DISCONNECTED && !busy) startActivity(Intent(this, RemoteActivity::class.java))
        Link.lastError?.let {
            showError(it)
            Link.lastError = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            data?.getStringExtra(ScanActivity.EXTRA_LINK)?.let { PairingLink.parse(it) }?.let { pair(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun scanQr() = startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)

    /**
     * airpointer://pair links (e.g. opened by the phone's own camera app). Returns true if it was one.
     * Any app or web page can open such a link, so the user confirms first: pairing with a stranger's
     * computer would send it whatever is typed on the remote.
     */
    private fun handlePairingLink(intent: Intent?): Boolean {
        val link = intent?.data?.toString()?.let { PairingLink.parse(it) } ?: return false
        intent.data = null // don't ask again when the activity is recreated
        AlertDialog.Builder(this)
            .setTitle("Pair with ${link.name}?")
            .setMessage("Only pair with your own computer, after clicking \"+ Pair\" on it.")
            .setPositiveButton("Pair") { _, _ -> pair(link) }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    // ---------- status banner ----------

    private fun showError(text: String) {
        statusBanner.visibility = View.VISIBLE
        statusBanner.setBackgroundResource(R.drawable.bg_banner_error)
        statusIcon.visibility = View.VISIBLE
        statusProgress.visibility = View.GONE
        status.text = text
    }

    private fun showProgress(text: String) {
        statusBanner.visibility = View.VISIBLE
        statusBanner.setBackgroundResource(R.drawable.bg_banner_info)
        statusIcon.visibility = View.GONE
        statusProgress.visibility = View.VISIBLE
        status.text = text
    }

    private fun hideStatus() {
        statusBanner.visibility = View.GONE
    }

    /** Busy: banner with a spinner, and a spinner on the row that was tapped. */
    private fun setBusy(b: Boolean, row: View? = null, text: String? = null) {
        busy = b
        btnConnect.isEnabled = !b
        row?.findViewById<View>(R.id.progress)?.visibility = if (b) View.VISIBLE else View.GONE
        row?.findViewById<View>(R.id.chevron)?.visibility = if (b) View.GONE else View.VISIBLE
        if (b && text != null) showProgress(text) else if (!b) hideStatus()
    }

    // ---------- lists ----------

    private fun addSteps(container: LinearLayout) {
        val steps = listOf(
            "Open AirPointer on your computer",
            "Click \"+ Pair\" there",
            "Scan the code with this phone",
        )
        steps.forEachIndexed { i, text ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(context, 5f), 0, Ui.dp(context, 5f))
                addView(TextView(context).apply {
                    this.text = "${i + 1}"
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.bg_step)
                }, LinearLayout.LayoutParams(Ui.dp(context, 26f), Ui.dp(context, 26f)))
                addView(TextView(context).apply {
                    this.text = text
                    textSize = 15f
                    setTextColor(0xE6FFFFFF.toInt())
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Ui.dp(context, 12f)
                })
            })
        }
    }

    private fun row(parent: LinearLayout, icon: Int, title: String, subtitle: String): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_computer, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.subtitle).text = subtitle
        parent.addView(v)
        return v
    }

    private fun showComputers() {
        val pcs = PcStore.all(this)
        hero.visibility = if (pcs.isEmpty()) View.VISIBLE else View.GONE
        computersSection.visibility = if (pcs.isEmpty()) View.GONE else View.VISIBLE
        pairedList.removeAllViews()
        for (pc in pcs) {
            val here = nearby.firstOrNull { it.id?.contentEquals(pc.id) == true }
            val where = when {
                here != null -> "On this network"
                pc.internetId != null -> "Reachable from anywhere"
                else -> "Home network"
            }
            val icon = if (pc.os == "macOS") R.drawable.lic_laptop else R.drawable.lic_monitor
            val v = row(pairedList, icon, pc.name, "$where · ${pc.os}")
            if (here != null) {
                v.findViewById<View>(R.id.dot).visibility = View.VISIBLE
                v.findViewById<View>(R.id.iconTile).setBackgroundResource(R.drawable.bg_icon_tile_ok)
                v.findViewById<ImageView>(R.id.icon).imageTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.ok))
            }
            v.setOnClickListener {
                val target = PcTarget(this, if (here != null) pc.withHost(here.host, here.port) else pc)
                connect(target, v, "Connecting to ${pc.name}…")
            }
            v.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Forget ${pc.name}?")
                    .setMessage("You can pair it again any time.")
                    .setPositiveButton("Forget") { _, _ ->
                        PcStore.remove(this, pc)
                        showComputers()
                        showNearby()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
    }

    /** Unpaired computers found on this network. */
    private fun showNearby() {
        serverList.removeAllViews()
        val unpaired = nearby.filter { s -> s.id == null || PcStore.find(this, s.id) == null }
        if (unpaired.isEmpty()) {
            serverList.addView(TextView(this).apply {
                text = if (scanning) "Looking for computers…" else "None found"
                setTextColor(getColor(R.color.text_muted))
                textSize = 14f
                setPadding(Ui.dp(context, 4f), Ui.dp(context, 10f), 0, 0)
            })
            return
        }
        for (s in unpaired) {
            val v = row(serverList, R.drawable.lic_wifi, s.name, "${s.os} · ${s.host}")
            v.findViewById<View>(R.id.badge).visibility = View.VISIBLE
            v.findViewById<View>(R.id.chevron).visibility = View.GONE
            v.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Pair with ${s.name}")
                    .setMessage("On ${s.name}, click \"+ Pair\" in AirPointer, then scan the code.")
                    .setPositiveButton("Scan code") { _, _ -> scanQr() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun toggleManual() {
        val open = manualForm.visibility != View.VISIBLE
        manualForm.visibility = if (open) View.VISIBLE else View.GONE
        manualChevron.animate().rotation(if (open) 180f else 0f).setDuration(150).start()
    }

    // ---------- actions ----------

    private fun scan() {
        if (scanning) return
        scanning = true
        btnScan.visibility = View.INVISIBLE
        scanProgress.visibility = View.VISIBLE
        showNearby()
        thread {
            val found = runCatching { Discovery.discover() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                nearby = found
                btnScan.visibility = View.VISIBLE
                scanProgress.visibility = View.GONE
                showComputers()
                showNearby()
            }
        }
    }

    /** Pairs with the computer in the QR code, then connects to it. */
    private fun pair(link: PairingLink) {
        if (busy) return
        setBusy(true, text = "Pairing with ${link.name}…")
        thread {
            try {
                val pc = link.pair(this)
                val target = PcTarget(this, pc)
                val sink = target.connect()
                runOnUiThread {
                    showComputers()
                    setBusy(false)
                    Toast.makeText(this, "Paired with ${pc.name}", Toast.LENGTH_SHORT).show()
                    openRemote(target, sink)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showComputers()
                    setBusy(false)
                    showError(failure(e, link.name, "Couldn't pair with ${link.name}."))
                }
            }
        }
    }

    /** Typed address: it must be one of the paired computers (TLS only trusts their keys). */
    private fun connectAddress(host: String, port: Int) {
        if (busy) return
        if (PcStore.all(this).isEmpty()) {
            showError("Pair first: scan the computer's code.")
            return
        }
        setBusy(true, text = "Connecting to $host…")
        thread {
            try {
                val sink = NetSink.connectTcp(this, listOf(host), port)
                val target = PcTarget(this, sink.pc!!)
                target.remember(host, port)
                runOnUiThread {
                    prefs.lastHost = host
                    prefs.lastPort = port
                    setBusy(false)
                    openRemote(target, sink)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    showError(failure(e, host, "Couldn't connect to $host:$port."))
                }
            }
        }
    }

    private fun connect(target: Link.Target, row: View, message: String) {
        if (busy) return
        setBusy(true, row, message)
        thread {
            try {
                val sink = target.connect()
                runOnUiThread {
                    setBusy(false, row)
                    openRemote(target, sink)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false, row)
                    showError(failure(e, target.name, "Couldn't connect to ${target.name}."))
                }
            }
        }
    }

    /** Turns a connection error into advice. */
    private fun failure(e: Exception, who: String, prefix: String): String = when {
        e is NetSink.HandshakeException -> e.message ?: prefix
        e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException ->
            "$prefix This computer isn't paired. Scan its code."
        else -> "$prefix Is AirPointer running on $who?"
    }

    private fun openRemote(target: Link.Target, sink: MouseSink) {
        Link.start(this, target, sink)
        startActivity(Intent(this, RemoteActivity::class.java))
    }

    companion object {
        private const val REQ_SCAN = 2
        private const val REQ_NOTIFY = 4
    }
}
