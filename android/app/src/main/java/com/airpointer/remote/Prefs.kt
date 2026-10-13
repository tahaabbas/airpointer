package com.airpointer.remote

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("airpointer", Context.MODE_PRIVATE)

    var touchSpeed: Float
        get() = sp.getFloat("touchSpeed", 1.0f)
        set(v) = sp.edit().putFloat("touchSpeed", v).apply()

    var airSpeed: Float
        get() = sp.getFloat("airSpeed", 1.0f)
        set(v) = sp.edit().putFloat("airSpeed", v).apply()

    var scrollSpeed: Float
        get() = sp.getFloat("scrollSpeed", 1.0f)
        set(v) = sp.edit().putFloat("scrollSpeed", v).apply()

    /** Steadiness filter for the air mouse: 0 = raw, 1 = very smooth. */
    var airSmoothing: Float
        get() = sp.getFloat("airSmoothing", 0.5f)
        set(v) = sp.edit().putFloat("airSmoothing", v).apply()

    var invertScroll: Boolean
        get() = sp.getBoolean("invertScroll", false)
        set(v) = sp.edit().putBoolean("invertScroll", v).apply()

    var volumeKeysClick: Boolean
        get() = sp.getBoolean("volumeKeysClick", true)
        set(v) = sp.edit().putBoolean("volumeKeysClick", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** After a click, air-mouse movement is frozen this long so the press jolt doesn't move the cursor. */
    var clickLockMs: Int
        get() = sp.getInt("clickLockMs", 300)
        set(v) = sp.edit().putInt("clickLockMs", v).apply()

    /** Hide Android's back/home/recents bar on the remote screen (swipe from the edge shows it briefly). */
    var hideNavBar: Boolean
        get() = sp.getBoolean("hideNavBar", false)
        set(v) = sp.edit().putBoolean("hideNavBar", v).apply()

    /** Holding LEFT this long latches the button down until it is tapped again. */
    var holdLockMs: Int
        get() = sp.getInt("holdLockMs", 5000)
        set(v) = sp.edit().putInt("holdLockMs", v).apply()

    /** Screen view image quality: 0 = performance, 1 = balanced, 2 = quality. Resolution always matches the computer. */
    var screenQuality: Int
        get() = sp.getInt("screenQuality", 1)
        set(v) = sp.edit().putInt("screenQuality", v).apply()

    /** Screen view frames per second (5–60). */
    var screenFps: Int
        get() = sp.getInt("screenFps", 20)
        set(v) = sp.edit().putInt("screenFps", v).apply()

    /** Screen view zoom: 1 = whole screen fits the touchpad. Pinching the pad in screen view changes it too. */
    var screenZoom: Float
        get() = sp.getFloat("screenZoom", 2f)
        set(v) = sp.edit().putFloat("screenZoom", v).apply()

    /** How opaque the touchpad is over the screen view: 0 = screen fully visible, 0.9 = mostly touchpad. */
    var padOpacity: Float
        get() = sp.getFloat("padOpacity", 0.35f)
        set(v) = sp.edit().putFloat("padOpacity", v).apply()

    var landscape: Boolean
        get() = sp.getBoolean("landscape", false)
        set(v) = sp.edit().putBoolean("landscape", v).apply()

    /**
     * The shortcut bar of one computer ([profile] = [Link.Profile.key]); each paired computer has its own.
     * A computer without its own set starts from the buttons of older versions (one set for all) if there
     * were any, otherwise from the defaults for its operating system.
     */
    fun shortcutsFor(profile: Link.Profile): List<Shortcut> =
        sp.getString("shortcuts:${profile.key}", null)?.let { ShortcutPresets.fromJson(it) }
            ?: sp.getString("shortcuts", null)?.let { ShortcutPresets.fromJson(it) }
            ?: ShortcutPresets.defaultsFor(profile.os)

    fun saveShortcuts(profile: Link.Profile, list: List<Shortcut>) =
        sp.edit().putString("shortcuts:${profile.key}", ShortcutPresets.toJson(list)).apply()

    var lastHost: String
        get() = sp.getString("lastHost", "") ?: ""
        set(v) = sp.edit().putString("lastHost", v).apply()

    var lastPort: Int
        get() = sp.getInt("lastPort", Protocol.DEFAULT_PORT)
        set(v) = sp.edit().putInt("lastPort", v).apply()
}
