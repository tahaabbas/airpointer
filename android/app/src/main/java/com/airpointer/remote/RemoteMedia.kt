package com.airpointer.remote

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Screen-view and sound toggles of the remote. They outlive the remote screen (rotation, switching apps:
 * the computer's sound keeps playing) and are reset whenever the link is closed or replaced.
 */
object RemoteMedia {
    /** The display button: the remote shows the computer screen behind the touchpad (it owns that stream). */
    var screenOn = false

    val soundOn: Boolean get() = sound != null

    /** Main thread: told when sound stops by itself, with the reason. */
    var onSoundStopped: ((String) -> Unit)? = null

    private var sound: MediaStream? = null
    private var player: PcmPlayer? = null

    /** Main thread. */
    fun startSound(context: Context, target: PcTarget) {
        if (sound != null) return
        val p = PcmPlayer()
        player = p
        sound = MediaStream(context, target, Protocol.MEDIA_AUDIO, object : MediaStream.Listener {
            override fun onMediaStatus(message: String?, fatal: Boolean) {
                if (fatal && player === p) {
                    stopSound()
                    onSoundStopped?.invoke(message ?: "Computer sound stopped")
                }
            }

            override fun onMediaFrame(type: Int, data: ByteArray, length: Int, backlog: Int) {
                when (type) {
                    Protocol.AUDIO_FORMAT -> {
                        val b = ByteBuffer.wrap(data, 0, length)
                        p.open(b.int, b.get().toInt())
                    }
                    Protocol.AUDIO_DATA -> p.play(data, length, backlog)
                }
            }
        })
    }

    /** Main thread. */
    fun stopSound() {
        sound?.stop()
        sound = null
        player?.release()
        player = null
    }

    fun reset() {
        screenOn = false
        stopSound()
    }
}

/** Plays the computer's 16-bit PCM with low latency, skipping audio that piles up behind (Wi-Fi hiccups). */
private class PcmPlayer {
    @Volatile private var track: AudioTrack? = null
    private var bytesPerSecond = 0
    @Volatile private var released = false

    /** Stream thread. */
    @Synchronized
    fun open(sampleRate: Int, channels: Int) {
        closeTrack()
        if (released) return
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        bytesPerSecond = sampleRate * channels * 2
        val size = max(AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), bytesPerSecond / 10)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        track = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(attrs, format, size, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            }
        }.getOrNull()?.also { it.play() }
    }

    /** Stream thread. Blocks while the phone's audio buffer is full, which paces the stream. */
    fun play(data: ByteArray, length: Int, backlog: Int) {
        val t = track ?: return
        if (backlog > bytesPerSecond / 4) return // more than 250 ms behind: drop this chunk to catch up
        t.write(data, 0, length)
    }

    @Synchronized
    fun release() {
        released = true
        closeTrack()
    }

    private fun closeTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.release() }
    }
}
