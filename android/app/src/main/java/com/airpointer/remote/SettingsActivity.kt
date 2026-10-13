package com.airpointer.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }

        // First thing on the page, in the accent pill style of "+ Pair".
        col.addView(TextView(this).apply {
            val accent = getColor(R.color.accent_light)
            text = Ui.iconText(this@SettingsActivity, R.drawable.ic_keyboard, "Shortcut buttons", accent)
            setTextColor(accent)
            textSize = 15f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_pill)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
                .apply { topMargin = dp(8); bottomMargin = dp(4) }
            setOnClickListener { startActivity(Intent(this@SettingsActivity, ShortcutsActivity::class.java)) }
        })

        col.addView(slider("Touchpad speed", prefs.touchSpeed, 0.3f, 3f) { prefs.touchSpeed = it })
        col.addView(slider("Air mouse speed", prefs.airSpeed, 0.3f, 3f) { prefs.airSpeed = it })
        col.addView(slider("Air mouse smoothing", prefs.airSmoothing, 0f, 1f, percent = true) { prefs.airSmoothing = it })
        col.addView(msSlider("Air mouse: hold still on click", prefs.clickLockMs, 5, 1000) { prefs.clickLockMs = it })
        col.addView(secondsSlider("Hold LEFT to lock it pressed after", prefs.holdLockMs, 1000, 15000) { prefs.holdLockMs = it })
        col.addView(slider("Scroll speed", prefs.scrollSpeed, 0.3f, 3f) { prefs.scrollSpeed = it })

        col.addView(toggle("Reverse scroll direction", prefs.invertScroll) { prefs.invertScroll = it })
        col.addView(toggle("Volume keys = left / right click", prefs.volumeKeysClick) { prefs.volumeKeysClick = it })
        col.addView(toggle("Vibration feedback", prefs.haptics) { prefs.haptics = it })
        col.addView(toggle("Hide navigation bar", prefs.hideNavBar) {
            prefs.hideNavBar = it
        })

        col.addView(section("Screen"))
        col.addView(choice("Quality", listOf("Performance", "Balanced", "Quality"), prefs.screenQuality) {
            prefs.screenQuality = it
        })
        col.addView(intSlider("Frames per second", prefs.screenFps, 5, 60, " fps") { prefs.screenFps = it })
        col.addView(slider("Zoom", prefs.screenZoom, ScreenView.MIN_ZOOM, ScreenView.MAX_ZOOM, percent = true) {
            prefs.screenZoom = it
        })
        col.addView(slider("Touchpad opacity", prefs.padOpacity, 0f, 0.9f, percent = true) { prefs.padOpacity = it })

        col.addView(TextView(this).apply {
            text = "\nAir mouse: point the top of the phone at the screen.\n\n" +
                "AirPointer ${packageManager.getPackageInfo(packageName, 0).versionName} · free and open source (MIT License)"
            setTextColor(getColor(R.color.text_muted))
            textSize = 13f
        })
        col.addView(TextView(this).apply {
            text = "Open-source licenses"
            setTextColor(getColor(R.color.accent_light))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { startActivity(Intent(this@SettingsActivity, LicensesActivity::class.java)) }
        })

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(layoutInflater.inflate(R.layout.view_top_bar, this, false))
            addView(ScrollView(context).apply { addView(col) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        Ui.topBar(this, "Settings")
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 17f
        setTextColor(getColor(R.color.text))
        paint.isFakeBoldText = true
        setPadding(0, dp(24), 0, dp(4))
    }

    /** A row of mutually exclusive buttons; the selected one is highlighted. */
    private fun choice(title: String, options: List<String>, selected: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = card()
        box.addView(TextView(this).apply { text = title; setTextColor(getColor(R.color.text)); textSize = 15f })
        // A segmented switch (like Keys / Text in the button editor): every option stays on one line.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_segments)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
        }
        val buttons = options.map { label ->
            TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setTextColor(getColor(R.color.text))
                textSize = 14f
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
        }
        fun show(sel: Int) = buttons.forEachIndexed { i, b ->
            b.setBackgroundResource(if (i == sel) R.drawable.bg_segment_on else R.drawable.bg_segment_off)
            b.typeface = if (i == sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener { show(i); onChange(i) }
            row.addView(b)
        }
        // One text size for all options, shrunk if needed so the longest label fits (large system fonts).
        row.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val first = buttons.first()
            val room = (r - l - row.paddingLeft - row.paddingRight) / buttons.size - first.paddingLeft - first.paddingRight
            val wanted = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            val paint = android.text.TextPaint(first.paint).apply { textSize = wanted; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            val widest = buttons.maxOf { paint.measureText(it.text.toString()) }
            val size = if (widest > room && room > 0) wanted * room / widest else wanted
            buttons.forEach { if (it.textSize != size) it.post { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size) } }
        }
        show(selected)
        box.addView(row)
        return box
    }

    /** Whole-number slider, e.g. frames per second. */
    private fun intSlider(title: String, value: Int, min: Int, max: Int, suffix: String, onChange: (Int) -> Unit): LinearLayout {
        val box = card()
        val label = TextView(this).apply { setTextColor(getColor(R.color.text)); textSize = 15f }
        label.text = "$title: $value$suffix"
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setPadding(0, dp(10), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title: ${min + p}$suffix"
                    if (fromUser) onChange(min + p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(label)
        box.addView(seek)
        return box
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun slider(
        title: String, value: Float, min: Float, max: Float, percent: Boolean = false, onChange: (Float) -> Unit,
    ): LinearLayout {
        val box = card()
        val label = TextView(this).apply { setTextColor(getColor(R.color.text)); textSize = 15f }
        fun fmt(v: Float) = if (percent) "${(v * 100).toInt()}%" else String.format("%.1f×", v)
        label.text = "$title: ${fmt(value)}"
        val seek = SeekBar(this).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
            setPadding(0, dp(10), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    label.text = "$title: ${fmt(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(label)
        box.addView(seek)
        return box
    }

    /** Millisecond slider in steps of 5 ms. */
    private fun msSlider(title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = card()
        val label = TextView(this).apply { setTextColor(getColor(R.color.text)); textSize = 15f }
        label.text = "$title: $value ms"
        val steps = (max - min) / 5
        val seek = SeekBar(this).apply {
            this.max = steps
            progress = ((value - min) / 5).coerceIn(0, steps)
            setPadding(0, dp(10), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * 5
                    label.text = "$title: $v ms"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(label)
        box.addView(seek)
        return box
    }

    /** Seconds slider in steps of 0.5 s (value stored in ms). */
    private fun secondsSlider(title: String, valueMs: Int, minMs: Int, maxMs: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = card()
        val label = TextView(this).apply { setTextColor(getColor(R.color.text)); textSize = 15f }
        fun fmt(ms: Int) = String.format("%.1f s", ms / 1000f)
        label.text = "$title: ${fmt(valueMs)}"
        val steps = (maxMs - minMs) / 500
        val seek = SeekBar(this).apply {
            max = steps
            progress = ((valueMs - minMs) / 500).coerceIn(0, steps)
            setPadding(0, dp(10), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = minMs + p * 500
                    label.text = "$title: ${fmt(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(label)
        box.addView(seek)
        return box
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val box = card()
        box.addView(Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(R.color.text))
            isChecked = value
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(40)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return box
    }
}
