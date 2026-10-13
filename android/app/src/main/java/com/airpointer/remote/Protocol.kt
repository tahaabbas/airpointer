package com.airpointer.remote

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Wire protocol shared with the desktop app (desktop/AirPointer/Core/Protocol.cs).
 * Frame = [u16 length BE][u8 type][payload], length = 1 + payload size.
 */
object Protocol {
    /**
     * 2 = shortcut-button combos, 3 = hold-to-repeat (COMBO_DOWN/COMBO_UP),
     * 4 = media connections (screen view + computer sound),
     * 5 = QR pairing + per-device keys instead of the PIN; Wi-Fi connections use TLS (see Secure).
     * An older PC app rejects us with "versions don't match" instead of silently ignoring them.
     */
    const val VERSION = 5
    const val DEFAULT_PORT = 50505
    const val DISCOVERY_PORT = 50506
    const val DISCOVERY_REQUEST = "AIRPTR?"
    const val DISCOVERY_REPLY_PREFIX = "AIRPTR!"
    val BT_SERVICE_UUID: UUID = UUID.fromString("7a1d3b2e-5c4f-4e8a-9b6d-2f0e1c3a4b5d")

    // Hello / MediaHello → AuthChallenge → AuthResponse → Welcome (see Secure.authenticate)
    const val HELLO = 0x01 // u8 version, str8 deviceName, 32 deviceId, 32 nonce
    const val WELCOME = 0x02 // u8 status, str8 name, str8 os [, str8 reason]
    const val PING = 0x03
    const val PONG = 0x04
    const val AUTH_CHALLENGE = 0x05 // 32 pcId, 32 nonce, bytes8 PC signature
    const val AUTH_RESPONSE = 0x06 // bytes8 phone signature
    const val PAIR_REQUEST = 0x07 // u8 version, bytes8 token, str8 deviceName, bytes16 phone public key
    const val PAIR_RESULT = 0x08 // u8 status, str8 name, str8 os, bytes16 PC public key
    const val MOVE = 0x10
    const val BUTTON = 0x11
    const val SCROLL = 0x12
    const val ZOOM = 0x13
    const val KEY = 0x20
    const val TEXT = 0x21
    const val ACTION = 0x22
    const val COMBO = 0x23 // str8 normalized key combo, e.g. "Ctrl+Shift+Esc" (see KeyCombo)
    const val COMBO_DOWN = 0x24 // str8 combo: press and hold; the computer auto-repeats it until COMBO_UP
    const val COMBO_UP = 0x25 // release the held combo

    // Media: a second Wi-Fi connection (see MediaStream). The first frame is MEDIA_HELLO; after an OK
    // Welcome the computer only sends large frames: [u32 length BE][u8 type][payload].
    const val MEDIA_HELLO = 0x30 // the HELLO fields + u8 kind, u8 quality, u8 fps, u8 codecs
    const val SCREEN_FRAME = 0x31 // i32 left, top, width, height (screen rect in cursor coordinates), JPEG bytes
    const val CURSOR_POS = 0x32 // i32 x, i32 y
    const val AUDIO_FORMAT = 0x33 // i32 sample rate, u8 channels; 16-bit little-endian PCM follows
    const val AUDIO_DATA = 0x34
    const val MEDIA_KEEP_ALIVE = 0x35
    const val VIDEO_FORMAT = 0x36 // i32 left, top, width, height (screen rect), i32 video width, height, u8 codec
    const val VIDEO_FRAME = 0x37 // u8 flags (1 = keyframe), H.264 / H.265 Annex-B bytes
    const val KEYFRAME_REQUEST = 0x38 // phone -> computer on the screen connection

    // Last byte of MEDIA_HELLO for the screen: what this phone can decode (bit flags).
    const val CODEC_JPEG = 1
    const val CODEC_H264 = 2
    const val CODEC_H265 = 4

    const val MEDIA_SCREEN = 1
    const val MEDIA_AUDIO = 2

    const val STATUS_OK = 0
    const val STATUS_BAD_VERSION = 2
    const val STATUS_UNAVAILABLE = 3 // Welcome carries a str8 reason after the name and OS
    const val STATUS_NOT_PAIRED = 4
    const val STATUS_AUTH_FAILED = 5
    const val STATUS_PAIRING_CLOSED = 6

    // Buttons / button actions
    const val BTN_LEFT = 1
    const val BTN_RIGHT = 2
    const val BTN_MIDDLE = 3
    const val ACT_UP = 0
    const val ACT_DOWN = 1
    const val ACT_CLICK = 2

    // Keys
    const val KEY_ENTER = 1
    const val KEY_BACKSPACE = 2
    const val KEY_ESCAPE = 3
    const val KEY_TAB = 4
    const val KEY_SPACE = 5
    const val KEY_DELETE = 6
    const val KEY_LEFT = 7
    const val KEY_RIGHT = 8
    const val KEY_UP = 9
    const val KEY_DOWN = 10
    const val KEY_PAGE_UP = 11
    const val KEY_PAGE_DOWN = 12
    const val KEY_HOME = 13
    const val KEY_END = 14
    const val KEY_VOLUME_UP = 20
    const val KEY_VOLUME_DOWN = 21
    const val KEY_MUTE = 22
    const val KEY_PLAY_PAUSE = 23
    const val KEY_NEXT_TRACK = 24
    const val KEY_PREV_TRACK = 25
    const val KEY_F5 = 30

    // Actions (mapped to the right shortcut per desktop OS)
    const val ACTION_TASK_VIEW = 1
    const val ACTION_SHOW_DESKTOP = 2
    const val ACTION_NEXT_APP = 3
    const val ACTION_PREV_APP = 4

    fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 1 + payload.size
        val out = ByteArray(2 + len)
        out[0] = (len shr 8).toByte()
        out[1] = len.toByte()
        out[2] = type.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    fun i16(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            val c = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (c shr 8).toByte()
            out[i * 2 + 1] = c.toByte()
        }
        return out
    }

    fun str8(s: String): ByteArray {
        var b = s.toByteArray(Charsets.UTF_8)
        if (b.size > 255) b = b.copyOf(255)
        return byteArrayOf(b.size.toByte()) + b
    }

    fun str16(s: String): ByteArray {
        var b = s.toByteArray(Charsets.UTF_8)
        if (b.size > 60000) b = b.copyOf(60000)
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    fun bytes8(b: ByteArray): ByteArray = byteArrayOf(b.size.toByte()) + b

    fun bytes16(b: ByteArray): ByteArray = byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b

    /** Hello fields (also the start of MEDIA_HELLO). */
    fun helloFields(deviceName: String, deviceId: ByteArray, nonce: ByteArray): ByteArray =
        byteArrayOf(VERSION.toByte()) + str8(deviceName) + deviceId + nonce

    fun pairRequest(token: ByteArray, deviceName: String, publicKey: ByteArray): ByteArray =
        frame(PAIR_REQUEST, byteArrayOf(VERSION.toByte()) + bytes8(token) + str8(deviceName) + bytes16(publicKey))

    /** Reads str8 / bytes8 / bytes16 fields of a payload in order. */
    class Reader(private val p: ByteArray, private var pos: Int = 0) {
        fun u8(): Int = p[pos++].toInt() and 0xFF
        fun bytes(n: Int): ByteArray = p.copyOfRange(pos, pos + n).also { pos += n }
        fun bytes8(): ByteArray = bytes(u8())
        fun bytes16(): ByteArray = bytes((u8() shl 8) or u8())
        fun str8(): String = String(bytes8(), Charsets.UTF_8)
        val remaining: Int get() = p.size - pos
    }

    class Frame(val type: Int, val payload: ByteArray)

    @Throws(IOException::class)
    fun readFrame(input: InputStream): Frame {
        val din = DataInputStream(input)
        val len = din.readUnsignedShort()
        if (len < 1) throw IOException("bad frame")
        val body = ByteArray(len)
        din.readFully(body)
        return Frame(body[0].toInt() and 0xFF, body.copyOfRange(1, len))
    }
}
