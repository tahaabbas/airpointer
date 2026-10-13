package com.airpointer.remote

import android.annotation.TargetApi
import android.os.Build

/** Sends input as Bluetooth HID reports (phone acts as a standard Bluetooth mouse/keyboard). */
@TargetApi(Build.VERSION_CODES.P)
class HidSink(override val label: String) : QueuedSink("hid-writer") {

    private var buttons = 0
    private var scrollV = 0
    private var scrollH = 0

    init {
        startWorker()
    }

    override fun onWorkerFailed(e: Exception) = Link.lost(this, "Bluetooth error: ${e.message}")

    override fun close() {
        stopWorker()
        HidManager.disconnect()
    }

    /** The host already went away: stop sending, but don't touch the (possibly re-established) link. */
    fun abandon() = stopWorker()

    override fun execute(op: Op) {
        when (op) {
            is Op.Move -> {
                var rx = op.dx
                var ry = op.dy
                do {
                    val sx = rx.coerceIn(-32767, 32767)
                    val sy = ry.coerceIn(-32767, 32767)
                    mouse(sx, sy)
                    rx -= sx; ry -= sy
                } while (rx != 0 || ry != 0)
            }
            is Op.Button -> {
                val bit = when (op.button) {
                    Protocol.BTN_LEFT -> 1
                    Protocol.BTN_RIGHT -> 2
                    Protocol.BTN_MIDDLE -> 4
                    else -> return
                }
                when (op.action) {
                    Protocol.ACT_DOWN -> { buttons = buttons or bit; mouse() }
                    Protocol.ACT_UP -> { buttons = buttons and bit.inv(); mouse() }
                    Protocol.ACT_CLICK -> {
                        buttons = buttons or bit; mouse()
                        buttons = buttons and bit.inv(); mouse()
                    }
                }
            }
            is Op.Scroll -> {
                // HID wheels report whole notches; keep the remainder for smooth accumulation.
                scrollV += op.v
                scrollH += op.h
                val nv = (scrollV / 120).coerceIn(-127, 127)
                val nh = (scrollH / 120).coerceIn(-127, 127)
                scrollV -= nv * 120
                scrollH -= nh * 120
                if (nv != 0 || nh != 0) mouse(wheel = nv, pan = nh)
            }
            is Op.Zoom -> {
                keyboard(MOD_CTRL, 0)
                mouse(wheel = op.steps.coerceIn(-127, 127))
                keyboard(0, 0)
            }
            is Op.Key -> pressKey(op.key)
            is Op.Text -> for (c in op.text) {
                val (mods, code) = charToHid(c) ?: continue
                keyboard(mods, code)
                keyboard(0, 0)
            }
            is Op.Action -> when (op.action) {
                // Windows-style shortcuts (the most common host for Bluetooth mouse mode).
                Protocol.ACTION_TASK_VIEW -> combo(MOD_GUI, 0x2B)
                Protocol.ACTION_SHOW_DESKTOP -> combo(MOD_GUI, 0x07)
                Protocol.ACTION_NEXT_APP -> combo(MOD_ALT, 0x2B)
                Protocol.ACTION_PREV_APP -> combo(MOD_ALT or MOD_SHIFT, 0x2B)
            }
            is Op.Combo -> sendCombo(op.combo)
            is Op.ComboDown -> holdCombo(op.combo)
            Op.ComboUp -> {
                keyboard(0, 0)
                HidManager.sendReport(HidManager.REPORT_CONSUMER, byteArrayOf(0, 0))
            }
            Op.Stop -> {}
        }
    }

    /** Plays a normalized combo ("Ctrl+Shift+Esc", "VolumeUp", "F5") as HID reports. */
    private fun sendCombo(combo: String) {
        val parts = combo.split('+')
        var mods = 0
        var main: String? = null
        for (p in parts) {
            when (p) {
                "Ctrl" -> mods = mods or MOD_CTRL
                "Shift" -> mods = mods or MOD_SHIFT
                "Alt" -> mods = mods or MOD_ALT
                "Win" -> mods = mods or MOD_GUI
                else -> main = p
            }
        }
        val key = main
        if (key == null) {
            // Modifiers only (e.g. "Win" opens Start).
            keyboard(mods, 0)
            keyboard(0, 0)
            return
        }
        CONSUMER[key]?.let { usage ->
            HidManager.sendReport(HidManager.REPORT_CONSUMER, byteArrayOf(usage.toByte(), (usage shr 8).toByte()))
            HidManager.sendReport(HidManager.REPORT_CONSUMER, byteArrayOf(0, 0))
            return
        }
        if (key == "Lock") {
            combo(MOD_GUI, 0x0F) // Win+L
            return
        }
        val usage = keyUsage(key) ?: return // not available over Bluetooth HID (e.g. Wi-Fi toggle, F13+)
        combo(mods, usage)
    }

    /** Presses a combo and leaves it down; the host's own keyboard repeat kicks in until ComboUp. */
    private fun holdCombo(combo: String) {
        var mods = 0
        var main: String? = null
        for (p in combo.split(',').first().trim().split('+')) {
            when (p) {
                "Ctrl" -> mods = mods or MOD_CTRL
                "Shift" -> mods = mods or MOD_SHIFT
                "Alt" -> mods = mods or MOD_ALT
                "Win" -> mods = mods or MOD_GUI
                else -> main = p
            }
        }
        val key = main
        if (key != null) {
            CONSUMER[key]?.let { usage ->
                HidManager.sendReport(HidManager.REPORT_CONSUMER, byteArrayOf(usage.toByte(), (usage shr 8).toByte()))
                return
            }
        }
        keyboard(mods, 0)
        val usage = key?.let { keyUsage(it) } ?: return
        keyboard(mods, usage)
    }

    private fun keyUsage(name: String): Int? {
        if (name.length == 1) {
            val c = name[0]
            return when (c) {
                in 'A'..'Z' -> 0x04 + (c - 'A')
                in '1'..'9' -> 0x1E + (c - '1')
                '0' -> 0x27
                else -> null
            }
        }
        if (name.startsWith("F") && name.length <= 3) {
            val n = name.substring(1).toIntOrNull()
            if (n != null && n in 1..12) return 0x3A + n - 1
            if (n != null) return null // F13-F24 are outside this keyboard descriptor
        }
        return NAMED_USAGES[name]
    }

    private fun combo(mods: Int, code: Int) {
        keyboard(mods, 0)
        keyboard(mods, code)
        keyboard(mods, 0)
        keyboard(0, 0)
    }

    private fun pressKey(key: Int) {
        val consumer = when (key) {
            Protocol.KEY_VOLUME_UP -> 0xE9
            Protocol.KEY_VOLUME_DOWN -> 0xEA
            Protocol.KEY_MUTE -> 0xE2
            Protocol.KEY_PLAY_PAUSE -> 0xCD
            Protocol.KEY_NEXT_TRACK -> 0xB5
            Protocol.KEY_PREV_TRACK -> 0xB6
            else -> 0
        }
        if (consumer != 0) {
            HidManager.sendReport(HidManager.REPORT_CONSUMER, byteArrayOf(consumer.toByte(), (consumer shr 8).toByte()))
            HidManager.sendReport(HidManager.REPORT_CONSUMER, byteArrayOf(0, 0))
            return
        }
        val code = when (key) {
            Protocol.KEY_ENTER -> 0x28
            Protocol.KEY_ESCAPE -> 0x29
            Protocol.KEY_BACKSPACE -> 0x2A
            Protocol.KEY_TAB -> 0x2B
            Protocol.KEY_SPACE -> 0x2C
            Protocol.KEY_DELETE -> 0x4C
            Protocol.KEY_RIGHT -> 0x4F
            Protocol.KEY_LEFT -> 0x50
            Protocol.KEY_DOWN -> 0x51
            Protocol.KEY_UP -> 0x52
            Protocol.KEY_PAGE_UP -> 0x4B
            Protocol.KEY_PAGE_DOWN -> 0x4E
            Protocol.KEY_HOME -> 0x4A
            Protocol.KEY_END -> 0x4D
            Protocol.KEY_F5 -> 0x3E
            else -> return
        }
        keyboard(0, code)
        keyboard(0, 0)
    }

    private fun mouse(dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0) {
        HidManager.sendReport(
            HidManager.REPORT_MOUSE,
            byteArrayOf(
                buttons.toByte(),
                dx.toByte(), (dx shr 8).toByte(),
                dy.toByte(), (dy shr 8).toByte(),
                wheel.toByte(), pan.toByte(),
            ),
        )
    }

    private fun keyboard(mods: Int, code: Int) {
        HidManager.sendReport(HidManager.REPORT_KEYBOARD, byteArrayOf(mods.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
    }

    companion object {
        const val MOD_CTRL = 0x01
        const val MOD_SHIFT = 0x02
        const val MOD_ALT = 0x04
        const val MOD_GUI = 0x08

        private val NAMED_USAGES = mapOf(
            "Enter" to 0x28, "Esc" to 0x29, "Backspace" to 0x2A, "Tab" to 0x2B, "Space" to 0x2C,
            "Minus" to 0x2D, "Equals" to 0x2E, "LeftBracket" to 0x2F, "RightBracket" to 0x30, "Backslash" to 0x31,
            "Semicolon" to 0x33, "Quote" to 0x34, "Backtick" to 0x35, "Comma" to 0x36, "Period" to 0x37, "Slash" to 0x38,
            "CapsLock" to 0x39, "PrintScreen" to 0x46, "ScrollLock" to 0x47, "Pause" to 0x48, "Insert" to 0x49,
            "Home" to 0x4A, "PageUp" to 0x4B, "Delete" to 0x4C, "End" to 0x4D, "PageDown" to 0x4E,
            "Right" to 0x4F, "Left" to 0x50, "Down" to 0x51, "Up" to 0x52, "NumLock" to 0x53, "Menu" to 0x65,
        )

        private val CONSUMER = mapOf(
            "VolumeUp" to 0xE9, "VolumeDown" to 0xEA, "Mute" to 0xE2, "PlayPause" to 0xCD,
            "NextTrack" to 0xB5, "PrevTrack" to 0xB6, "MediaStop" to 0xB7,
            "BrightnessUp" to 0x6F, "BrightnessDown" to 0x70,
        )

        private const val SHIFTED_DIGITS = ")!@#$%^&*("
        private const val PLAIN_SYMBOLS = "-=[]\\;'`,./"
        private const val SHIFTED_SYMBOLS = "_+{}|:\"~<>?"
        private val SYMBOL_CODES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)

        /** US keyboard layout mapping. Returns (modifiers, usage) or null when the character can't be typed. */
        fun charToHid(c: Char): Pair<Int, Int>? = when (c) {
            in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
            in 'A'..'Z' -> MOD_SHIFT to (0x04 + (c - 'A'))
            in '1'..'9' -> 0 to (0x1E + (c - '1'))
            '0' -> 0 to 0x27
            '\n' -> 0 to 0x28
            '\t' -> 0 to 0x2B
            ' ' -> 0 to 0x2C
            else -> {
                val d = SHIFTED_DIGITS.indexOf(c)
                val p = PLAIN_SYMBOLS.indexOf(c)
                val s = SHIFTED_SYMBOLS.indexOf(c)
                when {
                    d >= 0 -> MOD_SHIFT to (if (d == 0) 0x27 else 0x1E + d - 1)
                    p >= 0 -> 0 to SYMBOL_CODES[p]
                    s >= 0 -> MOD_SHIFT to SYMBOL_CODES[s]
                    else -> null
                }
            }
        }
    }
}
