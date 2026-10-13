package com.airpointer.remote

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Hardware H.264 decoding of screen view straight onto a [Surface] (the TextureView behind the touchpad).
 * Asynchronous MediaCodec: every decoded frame is shown as soon as it's ready, so the last frame of a burst
 * never waits in the decoder for the next one (the PC sends nothing while the screen is still).
 */
class VideoDecoder(surface: Surface, width: Int, height: Int, private val onError: () -> Unit) {
    private val thread = HandlerThread("airpointer-video").apply { start() }
    private val codec = MediaCodec.createDecoderByType(MIME)
    private val lock = Any()
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<ByteArray>()
    private var pts = 0L
    @Volatile var failed = false
        private set

    init {
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = synchronized(lock) {
                freeInputs.addLast(index)
                feed()
            }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { c.releaseOutputBuffer(index, true) }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = fail()
            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
        }, Handler(thread.looper))
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        }
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    /** Stream thread: one encoded frame (Annex-B). Throws if the decoder can't keep up or broke. */
    fun decode(data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        if (failed) throw IllegalStateException("decoder failed")
        if (pending.size > 30) throw IllegalStateException("decoder stalled")
        pending.addLast(data.copyOfRange(offset, offset + length))
        feed()
    }

    private fun feed() {
        try {
            while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
                val index = freeInputs.removeFirst()
                val frame = pending.removeFirst()
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(frame)
                codec.queueInputBuffer(index, 0, frame.size, pts, 0)
                pts += 16_666
            }
        } catch (e: IllegalStateException) {
            fail()
        }
    }

    private fun fail() {
        if (failed) return
        failed = true
        onError()
    }

    fun release() {
        failed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        thread.quitSafely()
    }

    companion object {
        const val MIME = "video/avc"

        /** True if the phone can decode H.264 (practically every phone). */
        val supported: Boolean by lazy {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
                }
            }.getOrDefault(false)
        }
    }
}
