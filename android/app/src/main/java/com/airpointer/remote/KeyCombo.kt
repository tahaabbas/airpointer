package com.airpointer.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Key combos typed by the user, e.g. "Alt+P", "Ctrl+Shift+Esc", "F5", "a", "VolumeUp".
 *
 * Combos are normalized to canonical key names and sent to the computer as text (Protocol.COMBO);
 * the desktop app parses the same names (desktop/AirPointer/Input/KeyNames.cs).
 */
object KeyCombo {

    val MODIFIERS = listOf("Ctrl", "Shift", "Alt", "Win")

    /** Named keys (besides A-Z, 0-9, F1-F24). */
    val NAMED = listOf(
        "Enter", "Esc", "Tab", "Space", "Backspace", "Delete", "Insert",
        "Left", "Right", "Up", "Down", "Home", "End", "PageUp", "PageDown",
        "PrintScreen", "CapsLock", "NumLock", "ScrollLock", "Pause", "Menu",
        "Minus", "Equals", "LeftBracket", "RightBracket", "Backslash", "Semicolon", "Quote", "Backtick",
        "Comma", "Period", "Slash",
        "VolumeUp", "VolumeDown", "Mute", "PlayPause", "NextTrack", "PrevTrack", "MediaStop",
    )

    /** Handled by the computer app as system actions; must be used alone. */
    val SYSTEM = listOf("BrightnessUp", "BrightnessDown", "WifiToggle", "AirplaneToggle", "Lock")

    /** Handled on the phone via the existing action/zoom messages; must be used alone. */
    val ACTIONS = mapOf(
        "TaskView" to Protocol.ACTION_TASK_VIEW,
        "ShowDesktop" to Protocol.ACTION_SHOW_DESKTOP,
        "SwitchApp" to Protocol.ACTION_NEXT_APP,
        "PrevApp" to Protocol.ACTION_PREV_APP,
    )
    val ZOOM = mapOf("ZoomIn" to 1, "ZoomOut" to -1)

    private val ALIASES = mapOf(
        "control" to "Ctrl", "ctl" to "Ctrl", "option" to "Alt", "opt" to "Alt",
        "cmd" to "Win", "command" to "Win", "meta" to "Win", "super" to "Win", "windows" to "Win", "start" to "Win",
        "return" to "Enter", "escape" to "Esc", "del" to "Delete", "ins" to "Insert", "bksp" to "Backspace",
        "spacebar" to "Space", "pgup" to "PageUp", "pgdn" to "PageDown", "pgdown" to "PageDown",
        "prtsc" to "PrintScreen", "prtscn" to "PrintScreen", "printscr" to "PrintScreen", "print" to "PrintScreen",
        "apps" to "Menu", "contextmenu" to "Menu", "break" to "Pause",
        "arrowleft" to "Left", "arrowright" to "Right", "arrowup" to "Up", "arrowdown" to "Down",
        "volup" to "VolumeUp", "vol+" to "VolumeUp", "voldown" to "VolumeDown", "vol-" to "VolumeDown",
        "volumemute" to "Mute", "play" to "PlayPause", "pause/play" to "PlayPause", "next" to "NextTrack",
        "prev" to "PrevTrack", "previous" to "PrevTrack", "stop" to "MediaStop",
        "brightup" to "BrightnessUp", "brightness+" to "BrightnessUp", "brightdown" to "BrightnessDown",
        "brightness-" to "BrightnessDown", "wifi" to "WifiToggle", "airplane" to "AirplaneToggle",
        "airplanemode" to "AirplaneToggle", "flightmode" to "AirplaneToggle", "lockscreen" to "Lock",
        "desktop" to "ShowDesktop", "alttab" to "SwitchApp", "zoom+" to "ZoomIn", "zoom-" to "ZoomOut",
    )

    private val SYMBOLS = mapOf(
        '-' to "Minus", '=' to "Equals", '[' to "LeftBracket", ']' to "RightBracket", '\\' to "Backslash",
        ';' to "Semicolon", '\'' to "Quote", '`' to "Backtick", ',' to "Comma", '.' to "Period", '/' to "Slash",
    )

    /** Characters typed with Shift on a US keyboard -> their unshifted key. */
    private val SHIFTED = mapOf(
        '!' to "1", '@' to "2", '#' to "3", '$' to "4", '%' to "5", '^' to "6", '&' to "7", '*' to "8",
        '(' to "9", ')' to "0", '_' to "Minus", '+' to "Equals", '{' to "LeftBracket", '}' to "RightBracket",
        '|' to "Backslash", ':' to "Semicolon", '"' to "Quote", '~' to "Backtick", '<' to "Comma",
        '>' to "Period", '?' to "Slash",
    )

    private val ALL_NAMES: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        (MODIFIERS + NAMED + SYSTEM + ACTIONS.keys + ZOOM.keys).forEach { m[it.lowercase()] = it }
        for (i in 1..24) m["f$i"] = "F$i"
        m.putAll(ALIASES)
        m
    }

    class Result(val normalized: String?, val error: String?)

    /** Parses and normalizes a combo. Returns an error message for invalid input. */
    fun parse(input: String): Result {
        var text = input.trim()
        if (text.isEmpty()) return Result(null, "Enter a key or combination, e.g. Alt+P")
        // "Ctrl++" or a lone "+" mean the plus key.
        if (text == "+") text = "Plus"
        else if (text.endsWith("++")) text = text.dropLast(1) + "Plus"

        val tokens = text.split('+').map { it.trim() }
        if (tokens.any { it.isEmpty() }) return Result(null, "Empty key in \"$input\"")

        val mods = LinkedHashSet<String>()
        var main: String? = null
        for (raw in tokens) {
            val keys = resolve(raw) ?: return Result(null, "Unknown key \"$raw\"")
            for (k in keys) {
                if (k in MODIFIERS) {
                    mods.add(k)
                } else {
                    if (main != null) return Result(null, "Only one non-modifier key per combo (\"$main\" and \"$k\")")
                    main = k
                }
            }
        }
        val m = main
        if (m != null && (m in SYSTEM || m in ACTIONS || m in ZOOM) && mods.isNotEmpty()) {
            return Result(null, "\"$m\" can't be combined with other keys")
        }
        val ordered = MODIFIERS.filter { it in mods } + listOfNotNull(m)
        return Result(ordered.joinToString("+"), null)
    }

    /** One user token -> canonical key name(s); a shifted symbol becomes Shift + key. */
    private fun resolve(token: String): List<String>? {
        if (token.equals("plus", ignoreCase = true)) return listOf("Shift", "Equals")
        if (token.length == 1) {
            val c = token[0]
            return when {
                c.isLetter() && c.code < 128 -> listOf(c.uppercaseChar().toString())
                c.isDigit() -> listOf(c.toString())
                c in SYMBOLS -> listOf(SYMBOLS.getValue(c))
                c in SHIFTED -> listOf("Shift", SHIFTED.getValue(c))
                else -> null
            }
        }
        return ALL_NAMES[token.lowercase().replace(" ", "")]?.let { listOf(it) }
    }

    /**
     * Parses a key sequence: "+" joins keys pressed together, "," separates steps pressed one after
     * another, e.g. "Ctrl+C, Alt+Tab, Ctrl+V". (Write "Comma" for the comma key itself.)
     */
    fun parseSequence(input: String): Result {
        val steps = input.split(',').map { it.trim() }
        if (steps.size > 1 && steps.any { it.isEmpty() }) {
            return Result(null, "Empty step between commas (for the comma key itself, write Comma)")
        }
        val out = ArrayList<String>()
        for (s in steps) {
            val r = parse(s)
            val n = r.normalized ?: return Result(null, if (steps.size > 1) "In \"$s\": ${r.error}" else r.error)
            out.add(n)
        }
        return Result(out.joinToString(", "), null)
    }

    /** Sends every step of a normalized sequence in order. */
    fun sendSequence(sink: MouseSink?, sequence: String) {
        sequence.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { send(sink, it) }
    }

    /** Sends one (normalized) combo, routing phone-side actions to the matching messages. */
    fun send(sink: MouseSink?, combo: String) {
        val s = sink ?: return
        ACTIONS[combo]?.let { s.action(it); return }
        ZOOM[combo]?.let { s.zoom(it); return }
        s.combo(combo)
    }
}

/**
 * A shortcut-bar button. [combo] is either a key sequence ("Alt+P", "Ctrl+C, Ctrl+V") or, when
 * [isText] is set, plain text to type ("sudo apt update"), optionally followed by Enter.
 * [icon] names a line icon from [Icons] (shown before the label; a blank label = icon only).
 */
data class Shortcut(
    val label: String,
    val combo: String,
    val isText: Boolean = false,
    val pressEnter: Boolean = false,
    val icon: String? = null,
) {
    fun run(sink: MouseSink?) {
        if (isText) {
            sink?.text(combo)
            if (pressEnter) sink?.key(Protocol.KEY_ENTER)
        } else {
            KeyCombo.sendSequence(sink, combo)
        }
    }

    enum class HoldKind { ONCE, KEYS, ZOOM }

    /**
     * What holding the button does: KEYS = held down on the computer with keyboard auto-repeat,
     * ZOOM = repeated zoom steps, ONCE = never repeats (text, sequences, toggles like Wi-Fi or Lock).
     */
    val holdKind: HoldKind
        get() = when {
            isText || combo.contains(',') -> HoldKind.ONCE
            combo in KeyCombo.ZOOM -> HoldKind.ZOOM
            combo in KeyCombo.ACTIONS -> HoldKind.ONCE
            combo == "WifiToggle" || combo == "AirplaneToggle" || combo == "Lock" -> HoldKind.ONCE
            else -> HoldKind.KEYS
        }

    /** Name for lists and dialogs (icon-only buttons have no label). */
    val title: String get() = label.ifBlank { NAMES[combo] ?: combo }

    /** One-line description for the editor list. */
    val summary: String get() = if (isText) "Types \"$combo\"${if (pressEnter) ", then Enter" else ""}" else "Presses $combo"

    private companion object {
        val NAMES = mapOf(
            "Backspace" to "Backspace", "Enter" to "Enter", "Left" to "Left arrow", "Right" to "Right arrow",
            "Up" to "Up arrow", "Down" to "Down arrow", "Mute" to "Mute", "PlayPause" to "Play / pause",
            "PrevTrack" to "Previous track", "NextTrack" to "Next track", "MediaStop" to "Stop",
            "ZoomIn" to "Zoom in", "ZoomOut" to "Zoom out",
        )
    }
}

/** Ready-made shortcuts offered in Settings; [DEFAULT_KEYS] are on the bar on first launch. */
object ShortcutPresets {
    private fun s(label: String, combo: String, icon: String? = null) = Shortcut(label, combo, icon = icon)

    val ALL: List<Pair<String, List<Shortcut>>> = listOf(
        "Keys" to listOf(
            s("", "Backspace", "delete"), s("", "Enter", "corner-down-left"), s("Esc", "Esc"),
            s("Tab", "Tab", "arrow-right-to-line"), s("Space", "Space", "space"), s("Del", "Delete"), s("Ins", "Insert"),
            s("", "Left", "arrow-left"), s("", "Right", "arrow-right"), s("", "Up", "arrow-up"), s("", "Down", "arrow-down"),
            s("Home", "Home", "arrow-left-to-line"), s("End", "End", "arrow-right-to-line"),
            s("PgUp", "PageUp", "chevrons-up"), s("PgDn", "PageDown", "chevrons-down"),
            s("PrtSc", "PrintScreen", "camera"), s("Caps", "CapsLock", "arrow-big-up"),
        ),
        "Function keys" to (1..12).map { s("F$it", "F$it") },
        "Media" to listOf(
            s("\u2212", "VolumeDown", "volume-1"), s("+", "VolumeUp", "volume-2"), s("", "Mute", "volume-x"),
            s("", "PlayPause", "play-pause"), s("", "PrevTrack", "skip-back"), s("", "NextTrack", "skip-forward"),
            s("", "MediaStop", "square"),
        ),
        "System" to listOf(
            s("\u2212", "BrightnessDown", "sun-dim"), s("+", "BrightnessUp", "sun"),
            s("Wi-Fi", "WifiToggle", "wifi"), s("Airplane", "AirplaneToggle", "plane"),
            s("Lock", "Lock", "lock"), s("Snip", "Win+Shift+S", "scissors"),
            s("Task view", "TaskView", "layout-grid"), s("Desktop", "ShowDesktop", "monitor"),
            s("Switch app", "SwitchApp", "arrow-left-right"), s("Close", "Alt+F4", "x"),
            s("", "ZoomIn", "zoom-in"), s("", "ZoomOut", "zoom-out"),
        ),
        "Editing" to listOf(
            s("Copy", "Ctrl+C", "copy"), s("Paste", "Ctrl+V", "clipboard-paste"), s("Cut", "Ctrl+X", "scissors"),
            s("Undo", "Ctrl+Z", "undo-2"), s("Redo", "Ctrl+Y", "redo-2"), s("Select all", "Ctrl+A", "square-dashed"),
            s("Save", "Ctrl+S", "save"), s("Find", "Ctrl+F", "search"),
        ),
        "Presentation" to listOf(
            s("Slideshow", "F5", "presentation"), s("From here", "Shift+F5", "play"), s("End show", "Esc", "square"),
            s("Black screen", "B", "monitor-off"),
        ),
    )

    /** The bar on first launch: (category, combo) of presets. */
    private val DEFAULT_KEYS = listOf(
        "Keys" to "Backspace", "Keys" to "Enter", "Keys" to "Esc", "Keys" to "Tab",
        "Keys" to "Left", "Keys" to "Right", "Keys" to "Up", "Keys" to "Down", "Presentation" to "F5",
        "Media" to "VolumeDown", "Media" to "VolumeUp", "Media" to "Mute", "Media" to "PlayPause",
        "Media" to "PrevTrack", "Media" to "NextTrack", "System" to "BrightnessDown", "System" to "BrightnessUp",
        "Editing" to "Ctrl+C", "Editing" to "Ctrl+V", "Editing" to "Ctrl+Z", "System" to "TaskView",
        "System" to "ShowDesktop", "System" to "SwitchApp", "System" to "ZoomIn", "System" to "ZoomOut",
    )

    /** For upgrading old buttons: one preset per combo, preferring the one with an icon (F5 -> "Slideshow"). */
    private val byCombo: Map<String, Shortcut> by lazy {
        val m = LinkedHashMap<String, Shortcut>()
        ALL.flatMap { it.second }.forEach { p -> if (m[p.combo]?.icon == null) m[p.combo] = p }
        m
    }

    val DEFAULTS: List<Shortcut> by lazy {
        val all = ALL.toMap()
        DEFAULT_KEYS.mapNotNull { (category, combo) -> all[category]?.firstOrNull { it.combo == combo } }
    }

    /** Presets as they should be for a computer running [os]: on a Mac, Cmd takes Ctrl's place. */
    fun presetsFor(os: String?): List<Pair<String, List<Shortcut>>> =
        if (os != "macOS") ALL else ALL.map { (category, list) -> category to list.map { forMac(it) } }

    fun defaultsFor(os: String?): List<Shortcut> = if (os != "macOS") DEFAULTS else DEFAULTS.map { forMac(it) }

    /** Cmd is "Win" in combos (see KeyNames on the computer). */
    private fun forMac(s: Shortcut): Shortcut = when (s.combo) {
        "Alt+F4" -> s.copy(combo = "Win+W")
        "Win+Shift+S" -> s.copy(combo = "Win+Shift+4")
        "Ctrl+Y" -> s.copy(combo = "Win+Shift+Z")
        else -> if (s.combo.startsWith("Ctrl+") && s.combo.length == 6) s.copy(combo = "Win+" + s.combo.substring(5)) else s
    }

    fun toJson(list: List<Shortcut>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("label", it.label).put("combo", it.combo).put("text", it.isText)
                    .put("enter", it.pressEnter).apply { it.icon?.let { i -> put("icon", i) } },
            )
        }
    }.toString()

    fun fromJson(json: String): List<Shortcut>? = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                upgrade(
                    Shortcut(
                        it.getString("label"), it.getString("combo"), it.optBoolean("text"), it.optBoolean("enter"),
                        it.optString("icon").takeIf { n -> n.isNotEmpty() },
                    ),
                )
            }
        }
    }.getOrNull()

    /** Older versions used emoji in labels: give saved buttons the matching icon and a clean label instead. */
    private fun upgrade(s: Shortcut): Shortcut {
        if (s.icon != null || !hasSymbols(s.label)) return s
        val preset = if (s.isText) null else byCombo[s.combo]
        val clean = s.label.filter { !isSymbol(it) }.trim()
        return when {
            preset == null -> s.copy(label = clean.ifEmpty { s.combo })
            clean in OLD_PRESET_LABELS -> preset // untouched preset: the new label and icon
            else -> s.copy(label = clean, icon = preset.icon) // renamed by the user: keep the name, add the icon
        }
    }

    /** Labels of the old emoji presets, with the emoji removed. */
    private val OLD_PRESET_LABELS = setOf(
        "", "Enter", "Vol −", "Vol +", "Mute", "Bright −", "Bright +", "Wi-Fi", "Airplane", "Lock", "Snip",
        "Slideshow", "From here", "End show",
    )

    private fun isSymbol(c: Char) = Character.isSurrogate(c) || c.code in 0x2190..0x2BFF || c == '\uFE0F'

    private fun hasSymbols(t: String) = t.any { isSymbol(it) && it != '\u2212' }
}
