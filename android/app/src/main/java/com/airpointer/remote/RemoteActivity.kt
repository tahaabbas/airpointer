package com.airpointer.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.min

class RemoteActivity : Activity(), TouchpadView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var air: AirMouse
    private lateinit var touchpad: TouchpadView
    private lateinit var screenView: ScreenView
    private lateinit var screenLayer: View
    private lateinit var btnDisplay: ImageButton
    private lateinit var btnSound: ImageButton
    private lateinit var connStatus: TextView
    private lateinit var btnAir: ImageButton
    private lateinit var btnKeyboardBar: Button
    private lateinit var keyboardRow: View
    private lateinit var textInput: EditText
    /** Portrait: one sideways-scrolling row (LinearLayout). Landscape: wrapping lines (FlowLayout). */
    private lateinit var quickKeys: ViewGroup

    private var accX = 0f
    private var accY = 0f
    private var accScrollV = 0f
    private var accScrollH = 0f
    private var airWanted = false
    private var listening = false

    // LEFT hold-to-lock
    private lateinit var leftButton: HoldButton
    private var holdStart = 0L
    private var holdLockMs = 5000
    private var ignoreLeftUntilUp = false

    /** Advances LEFT's progress bar; latches the button when the hold time is reached. */
    private val holdTick = object : Runnable {
        override fun run() {
            val p = (SystemClock.uptimeMillis() - holdStart).toFloat() / holdLockMs
            if (p >= 1f) {
                leftButton.latched = true
                if (prefs.haptics) leftButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } else {
                leftButton.progress = p
                leftButton.postDelayed(this, 16)
            }
        }
    }

    // Live typing from the big keyboard bar (keys go straight to the computer)
    private lateinit var keyCapture: KeyCaptureView
    private var liveTyping = false
    private var imeWasVisible = false

    // Air-mouse locks: while typing, and briefly around every click.
    private var keyboardOpen = false
    private var airLockUntil = 0L
    private var clickLockMs = 300

    // Screen view: the stream runs only while this screen is visible.
    private var screenStream: MediaStream? = null
    private var screenStreamSettings = ""
    private var resumed = false

    private val sink: MouseSink? get() = Link.sink

    private val linkListener: (Link.State, String?) -> Unit = { state, message ->
        if (state == Link.State.DISCONNECTED) {
            if (message != null) {
                // The computer rejected us (e.g. this phone was removed there): go back to the connect screen.
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            } else {
                // The user disconnected (power button or notification): leave the app to the home screen.
                finishAffinity()
            }
        } else {
            showLinkState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Link.state == Link.State.DISCONNECTED) {
            finish()
            return
        }
        prefs = Prefs(this)
        requestedOrientation = if (prefs.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                               else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_remote)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        connStatus = findViewById(R.id.connStatus)
        Link.addListener(linkListener)
        listening = true
        showLinkState()

        touchpad = findViewById(R.id.touchpad)
        touchpad.listener = this
        findViewById<ScrollStripView>(R.id.scrollStrip).onScroll = { dy -> scrollBy(dy, 0f) }

        // Toolbar
        air = AirMouse(this) { dx, dy -> if (!airLocked()) moveBy(dx, dy) }
        btnAir = findViewById(R.id.btnAir)
        btnAir.setOnClickListener {
            if (!air.available) {
                Toast.makeText(this, "This phone has no gyroscope, so air mouse isn't available.", Toast.LENGTH_LONG).show()
            } else {
                setAir(!airWanted)
                buzz(it)
            }
        }
        if (!air.available) btnAir.alpha = 0.4f
        findViewById<ImageButton>(R.id.btnRemoteSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener {
            prefs.landscape = !prefs.landscape
            // Changing orientation recreates this screen with the matching layout.
            requestedOrientation = if (prefs.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        findViewById<ImageButton>(R.id.btnKeyboard).setOnClickListener {
            if (liveTyping) stopLiveTyping()
            if (keyboardOpen) closeKeyboard() else openKeyboard()
        }
        findViewById<ImageButton>(R.id.btnDisconnect).setOnClickListener { disconnect() }

        // Screen view + computer sound (Wi-Fi to the PC app only)
        screenView = findViewById(R.id.screenView)
        screenLayer = findViewById(R.id.screenLayer)
        setupScreenLayer()
        btnDisplay = findViewById(R.id.btnDisplay)
        btnSound = findViewById(R.id.btnSound)
        if (Link.pcTarget == null) {
            btnDisplay.alpha = 0.4f
            btnSound.alpha = 0.4f
        }
        btnDisplay.setOnClickListener {
            if (!mediaAvailable()) return@setOnClickListener
            buzz(it)
            RemoteMedia.screenOn = !RemoteMedia.screenOn
            if (!RemoteMedia.screenOn) screenView.clear()
            applyScreenView()
        }
        btnSound.setOnClickListener {
            if (!mediaAvailable()) return@setOnClickListener
            buzz(it)
            val t = Link.pcTarget ?: return@setOnClickListener
            if (RemoteMedia.soundOn) RemoteMedia.stopSound() else RemoteMedia.startSound(this, t)
            showSoundState()
        }
        applyScreenView()
        showSoundState()

        // Mouse buttons
        leftButton = findViewById(R.id.btnLeft)
        setupLeftButton()
        holdButton(findViewById(R.id.btnMiddle), Protocol.BTN_MIDDLE)
        holdButton(findViewById(R.id.btnRight), Protocol.BTN_RIGHT)

        // Big keyboard bar = live typing; the small toolbar icon = text box + Send.
        btnKeyboardBar = findViewById(R.id.btnKeyboardBar)
        btnKeyboardBar.text = keyboardLabel()
        keyboardRow = findViewById(R.id.keyboardRow)
        textInput = findViewById(R.id.textInput)
        keyCapture = findViewById(R.id.keyCapture)
        keyCapture.onText = { sink?.text(it) }
        keyCapture.onBackspace = { sink?.key(Protocol.KEY_BACKSPACE) }
        keyCapture.onEnter = {
            sink?.key(Protocol.KEY_ENTER)
            stopLiveTyping()
        }
        btnKeyboardBar.setOnClickListener { if (liveTyping) stopLiveTyping() else startLiveTyping() }
        // Notice when the keyboard is dismissed with the system Back gesture.
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = imeVisible()
            if (liveTyping && imeWasVisible && !visible) stopLiveTyping()
            imeWasVisible = visible
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendTypedText() }
        textInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                sendTypedText(); true
            } else false
        }

        quickKeys = findViewById(R.id.quickKeys)

        if (savedInstanceState?.getBoolean(STATE_AIR) == true && air.available) setAir(true)
        // After rotating, the computer still holds the latched button; keep showing that.
        if (savedInstanceState?.getBoolean(STATE_LEFT_LATCHED) == true) leftButton.latched = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AIR, airWanted)
        outState.putBoolean(STATE_LEFT_LATCHED, leftButton.latched)
    }

    /** Over the internet the label can change from "relayed" to direct once hole punching succeeds. */
    private val labelRefresh = object : Runnable {
        override fun run() {
            showLinkState()
            connStatus.postDelayed(this, 3000)
        }
    }

    private fun showLinkState() {
        if (Link.state == Link.State.RECONNECTING) {
            connStatus.text = "Reconnecting…"
            connStatus.setTextColor(0xFFD29922.toInt())
            connStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_warn, 0, 0, 0)
        } else {
            connStatus.text = Link.name
            connStatus.setTextColor(getColor(R.color.ok))
            connStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_ok, 0, 0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Link.state == Link.State.DISCONNECTED) {
            finish()
            return
        }
        showLinkState()
        connStatus.postDelayed(labelRefresh, 3000)
        applyNavBar()
        air.speed = prefs.airSpeed
        air.smoothing = prefs.airSmoothing
        clickLockMs = prefs.clickLockMs
        holdLockMs = prefs.holdLockMs
        touchpad.haptics = prefs.haptics
        touchpad.padOpacity = prefs.padOpacity
        screenView.zoom = prefs.screenZoom
        buildShortcuts() // picks up edits made in Settings
        if (airWanted) air.start()
        resumed = true
        applyScreenView() // (re)starts the screen stream, with any new quality / fps from Settings
        RemoteMedia.onSoundStopped = { reason ->
            showSoundState()
            Toast.makeText(this, "Computer sound: $reason", Toast.LENGTH_LONG).show()
        }
        showSoundState()
    }

    override fun onPause() {
        super.onPause()
        connStatus.removeCallbacks(labelRefresh)
        // Sensors off while the screen isn't visible (saves battery, avoids stray movement).
        air.stop()
        // Same for the screen stream (Wi-Fi data); the computer's sound keeps playing.
        resumed = false
        stopScreenStream()
        RemoteMedia.onSoundStopped = null
        prefs.screenZoom = screenView.zoom
    }

    override fun onDestroy() {
        super.onDestroy()
        if (listening) Link.removeListener(linkListener)
        if (::air.isInitialized) air.stop()
        stopScreenStream()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (liveTyping) {
            stopLiveTyping()
            return
        }
        if (keyboardOpen) {
            closeKeyboard()
            return
        }
        // Back keeps the connection alive in the background; only "Disconnect" ends it.
        moveTaskToBack(true)
    }

    /** Power button: disconnect and close the app, back to the phone's home screen. */
    private fun disconnect() {
        air.stop()
        Link.disconnect()
        finishAffinity()
    }

    // ---------- navigation bar ----------

    private fun applyNavBar() {
        val hide = prefs.hideNavBar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = window.insetsController ?: return
            if (hide) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.navigationBars())
            } else {
                c.show(WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (hide) View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-apply after dialogs/keyboard, which can bring the bar back.
        if (hasFocus && ::prefs.isInitialized) applyNavBar()
    }

    // ---------- screen view + computer sound ----------

    private fun mediaAvailable(): Boolean {
        if (Link.pcTarget != null) return true
        Toast.makeText(this, "Not available over Bluetooth", Toast.LENGTH_LONG).show()
        return false
    }

    /** Shows or hides the computer screen behind the touchpad; off = the touchpad exactly as without screen view. */
    private fun applyScreenView() {
        val on = RemoteMedia.screenOn && Link.pcTarget != null
        btnDisplay.setBackgroundResource(if (on) R.drawable.bg_btn_on else R.drawable.bg_btn)
        touchpad.screenMode = on
        screenLayer.visibility = if (on) View.VISIBLE else View.GONE
        if (on && resumed) startScreenStream() else stopScreenStream()
    }

    private fun startScreenStream() {
        val t = Link.pcTarget ?: return
        val settings = "${prefs.screenQuality}/${prefs.screenFps}"
        if (screenStream != null && settings == screenStreamSettings) return
        stopScreenStream()
        screenStreamSettings = settings
        val view = screenView
        screenStream = MediaStream(this, t, Protocol.MEDIA_SCREEN, object : MediaStream.Listener {
            override fun onMediaStatus(message: String?, fatal: Boolean) {
                view.message = message
            }

            override fun onMediaFrame(type: Int, data: ByteArray, length: Int, backlog: Int) =
                view.onMediaFrame(type, data, length)
        }, quality = prefs.screenQuality, fps = prefs.screenFps)
        val stream = screenStream
        view.requestKeyframe = { stream?.send(Protocol.frame(Protocol.KEYFRAME_REQUEST)) }
    }

    private fun stopScreenStream() {
        screenStream?.stop()
        screenStream = null
    }

    /** Rounded like the touchpad on top; the video surface is handed to the screen view while it exists. */
    private fun setupScreenLayer() {
        val radius = 18f * resources.displayMetrics.density
        screenLayer.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        screenLayer.clipToOutline = true
        val video = findViewById<android.view.TextureView>(R.id.screenVideo)
        screenView.video = video
        video.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) =
                screenView.setSurface(android.view.Surface(st))

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                screenView.setSurface(null)
                return true
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    private fun showSoundState() {
        val on = RemoteMedia.soundOn
        btnSound.setImageResource(if (on) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
        btnSound.setBackgroundResource(if (on) R.drawable.bg_btn_on else R.drawable.bg_btn)
    }

    override fun onPadViewZoom(factor: Float) {
        screenView.zoom *= factor
    }

    // ---------- air mouse ----------

    private fun setAir(on: Boolean) {
        airWanted = on
        if (on) air.start() else air.stop()
        btnAir.setBackgroundResource(if (on) R.drawable.bg_btn_on else R.drawable.bg_btn)
    }

    private fun airLocked() = keyboardOpen || liveTyping || SystemClock.uptimeMillis() < airLockUntil

    /** Freeze the air cursor briefly so the jolt of pressing doesn't move it (double-clicks land on target). */
    private fun lockAirForClick() {
        if (airWanted) airLockUntil = SystemClock.uptimeMillis() + clickLockMs
    }

    // ---------- movement ----------

    private fun moveBy(dx: Float, dy: Float) {
        accX += dx
        accY += dy
        val ix = accX.toInt()
        val iy = accY.toInt()
        if (ix != 0 || iy != 0) {
            accX -= ix
            accY -= iy
            sink?.move(ix, iy)
        }
    }

    private fun scrollBy(dyDp: Float, dxDp: Float) {
        // Natural scrolling (content follows the finger) unless inverted; 120 units = one wheel notch.
        val k = 3.2f * prefs.scrollSpeed * (if (prefs.invertScroll) -1f else 1f)
        accScrollV += dyDp * k
        accScrollH += -dxDp * k
        val v = accScrollV.toInt()
        val h = accScrollH.toInt()
        if (v != 0 || h != 0) {
            accScrollV -= v
            accScrollH -= h
            sink?.scroll(v, h)
        }
    }

    override fun onPadMove(dxDp: Float, dyDp: Float) {
        // Pointer acceleration: slow finger = precise, fast flick = covers multiple screens.
        val d = hypot(dxDp, dyDp)
        val factor = prefs.touchSpeed * 1.6f * (1f + 0.09f * min(d, 25f))
        moveBy(dxDp * factor, dyDp * factor)
    }

    override fun onPadScroll(dyDp: Float, dxDp: Float) = scrollBy(dyDp, dxDp)

    /** If LEFT is latched, releases it (drop) and returns true. */
    private fun releaseLeftLatch(): Boolean {
        if (!leftButton.latched) return false
        leftButton.latched = false
        lockAirForClick()
        sink?.button(Protocol.BTN_LEFT, Protocol.ACT_UP)
        return true
    }

    private var ignorePadLeftUp = false
    private var ignoreVolumeUpRelease = false

    override fun onPadClick(button: Int) {
        if (button == Protocol.BTN_LEFT && releaseLeftLatch()) return // tap = drop while latched
        lockAirForClick()
        sink?.button(button, Protocol.ACT_CLICK)
    }

    override fun onPadButton(button: Int, down: Boolean) {
        if (button == Protocol.BTN_LEFT) {
            if (down && releaseLeftLatch()) { ignorePadLeftUp = true; return }
            if (!down && ignorePadLeftUp) { ignorePadLeftUp = false; return }
        }
        lockAirForClick()
        sink?.button(button, if (down) Protocol.ACT_DOWN else Protocol.ACT_UP)
    }

    override fun onPadZoom(steps: Int) {
        sink?.zoom(steps)
    }

    override fun onPadSwipe3(action: Int) {
        sink?.action(action)
    }

    // ---------- buttons ----------

    private fun buzz(v: View) {
        if (prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Physical-style button: pressed = button down, released = button up (so you can hold it to drag). */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdButton(v: View, button: Int) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    buzz(view)
                    lockAirForClick()
                    sink?.button(button, Protocol.ACT_DOWN)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    lockAirForClick()
                    sink?.button(button, Protocol.ACT_UP)
                }
            }
            true
        }
    }

    /** Volume keys act as left (up) and right (down) mouse buttons — handy while pointing in air mode. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (prefs.volumeKeysClick && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val button = if (code == KeyEvent.KEYCODE_VOLUME_UP) Protocol.BTN_LEFT else Protocol.BTN_RIGHT
            when {
                // Volume Up on a latched LEFT = release it (and ignore that key's own release).
                button == Protocol.BTN_LEFT && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                    releaseLeftLatch() -> ignoreVolumeUpRelease = true
                button == Protocol.BTN_LEFT && event.action == KeyEvent.ACTION_UP && ignoreVolumeUpRelease ->
                    ignoreVolumeUpRelease = false
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                    lockAirForClick()
                    sink?.button(button, Protocol.ACT_DOWN)
                }
                event.action == KeyEvent.ACTION_UP -> {
                    lockAirForClick()
                    sink?.button(button, Protocol.ACT_UP)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Shortcut bar: the user's buttons from Settings; the trailing ＋ opens the editor. */
    private fun buildShortcuts() {
        quickKeys.removeAllViews()
        val density = resources.displayMetrics.density
        fun params(): ViewGroup.LayoutParams =
            if (quickKeys is FlowLayout) {
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * density).toInt())
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = (6 * density).toInt() }
            }
        for (s in prefs.shortcutsFor(Link.profile)) { // each computer has its own bar
            val b = Button(this, null, 0, R.style.ShortcutBtn)
            b.text = Ui.shortcutFace(this, s, getColor(R.color.text))
            b.contentDescription = s.title
            b.layoutParams = params()
            b.setOnTouchListener(ShortcutTouch(s))
            quickKeys.addView(b)
        }
        val add = Button(this, null, 0, R.style.ShortcutBtn)
        add.text = Ui.iconText(this, R.drawable.lic_plus, "", getColor(R.color.text))
        add.contentDescription = "Add or edit shortcut buttons"
        add.layoutParams = params()
        add.setOnClickListener {
            startActivity(Intent(this, ShortcutsActivity::class.java).putExtra(ShortcutsActivity.EXTRA_PROFILE, Link.profile))
        }
        quickKeys.addView(add)
    }

    /**
     * Shortcut button that behaves like a keyboard key:
     *  - quick tap: the shortcut runs once (on release)
     *  - press and hold: the key goes down on the computer and auto-repeats until released
     *    (e.g. hold ⌫ to keep deleting); zoom repeats in steps; toggles/text/sequences never repeat.
     * The hold only starts after [HOLD_START_MS], so dragging to scroll the shortcut bar never presses a key.
     */
    private inner class ShortcutTouch(private val s: Shortcut) : View.OnTouchListener {
        private var holding = false
        private var view: View? = null
        private val startHold = Runnable { beginHold() }
        private val zoomRepeat = object : Runnable {
            override fun run() {
                KeyCombo.send(sink, s.combo)
                view?.postDelayed(this, ZOOM_REPEAT_MS)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view = v
                    v.isPressed = true
                    holding = false
                    if (s.holdKind != Shortcut.HoldKind.ONCE) v.postDelayed(startHold, HOLD_START_MS)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(startHold)
                    if (holding) endHold() else { buzz(v); s.run(sink) } // quick tap
                }
                MotionEvent.ACTION_CANCEL -> { // e.g. the bar started scrolling
                    v.isPressed = false
                    v.removeCallbacks(startHold)
                    if (holding) endHold()
                }
            }
            return true
        }

        private fun beginHold() {
            val v = view ?: return
            holding = true
            buzz(v)
            when (s.holdKind) {
                Shortcut.HoldKind.KEYS -> sink?.comboDown(s.combo)
                Shortcut.HoldKind.ZOOM -> {
                    KeyCombo.send(sink, s.combo)
                    v.postDelayed(zoomRepeat, ZOOM_FIRST_REPEAT_MS)
                }
                Shortcut.HoldKind.ONCE -> {}
            }
        }

        private fun endHold() {
            holding = false
            view?.removeCallbacks(zoomRepeat)
            if (s.holdKind == Shortcut.HoldKind.KEYS) sink?.comboUp()
        }
    }

    /**
     * LEFT: press = button down, release = button up (click/drag). Holding it for the configured time
     * fills the progress bar and latches the button down; the next tap releases it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLeftButton() {
        leftButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    buzz(v)
                    lockAirForClick()
                    if (leftButton.latched) {
                        leftButton.latched = false
                        ignoreLeftUntilUp = true
                        sink?.button(Protocol.BTN_LEFT, Protocol.ACT_UP)
                    } else {
                        sink?.button(Protocol.BTN_LEFT, Protocol.ACT_DOWN)
                        holdLockMs = prefs.holdLockMs // always the current Settings value
                        holdStart = SystemClock.uptimeMillis()
                        leftButton.removeCallbacks(holdTick)
                        leftButton.post(holdTick)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    leftButton.removeCallbacks(holdTick)
                    when {
                        ignoreLeftUntilUp -> ignoreLeftUntilUp = false
                        !leftButton.latched -> {
                            leftButton.progress = 0f
                            lockAirForClick()
                            sink?.button(Protocol.BTN_LEFT, Protocol.ACT_UP)
                        }
                    }
                }
            }
            true
        }
    }

    // ---------- live typing (big keyboard bar) ----------

    private fun startLiveTyping() {
        if (keyboardOpen) closeKeyboard()
        liveTyping = true
        btnKeyboardBar.text = Ui.iconText(this, R.drawable.ic_keyboard, "Typing to computer…  (tap to close)", getColor(R.color.text))
        btnKeyboardBar.setBackgroundResource(R.drawable.bg_btn_on)
        keyCapture.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyCapture.post {
            imm.restartInput(keyCapture)
            imm.showSoftInput(keyCapture, 0)
        }
    }

    private fun stopLiveTyping() {
        if (!liveTyping) return
        liveTyping = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(keyCapture.windowToken, 0)
        keyCapture.clearFocus()
        btnKeyboardBar.text = keyboardLabel()
        btnKeyboardBar.setBackgroundResource(R.drawable.bg_btn)
        // Short grace period so putting the keyboard away doesn't jump the air cursor.
        airLockUntil = SystemClock.uptimeMillis() + clickLockMs
    }

    private fun keyboardLabel() = Ui.iconText(this, R.drawable.ic_keyboard, "Keyboard", getColor(R.color.text))

    private fun imeVisible(): Boolean {
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        val r = Rect()
        decor.getWindowVisibleDisplayFrame(r)
        return decor.height - r.bottom > decor.height * 0.15f
    }

    // ---------- keyboard (toolbar icon: text box + Send) ----------

    /** Shows the text box + phone keyboard and locks the air cursor where it is until the text is sent. */
    private fun openKeyboard() {
        keyboardOpen = true
        btnKeyboardBar.visibility = View.GONE
        keyboardRow.visibility = View.VISIBLE
        textInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        textInput.post { imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun closeKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textInput.windowToken, 0)
        textInput.setText("")
        textInput.clearFocus()
        keyboardRow.visibility = View.GONE
        btnKeyboardBar.visibility = View.VISIBLE
        // Unlock only after the keyboard animation, so the hand movement of putting it away doesn't jump the cursor.
        airLockUntil = SystemClock.uptimeMillis() + clickLockMs
        keyboardOpen = false
    }

    private fun sendTypedText() {
        val t = textInput.text.toString()
        if (t.isNotEmpty()) sink?.text(t) else sink?.key(Protocol.KEY_ENTER)
        closeKeyboard()
    }

    companion object {
        private const val STATE_AIR = "air"
        private const val STATE_LEFT_LATCHED = "leftLatched"
        private const val HOLD_START_MS = 150L
        private const val ZOOM_FIRST_REPEAT_MS = 500L
        private const val ZOOM_REPEAT_MS = 200L
    }
}
