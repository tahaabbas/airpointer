package com.airpointer.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * The computer screen, shown behind the (translucent) touchpad while screen view is on.
 *
 * Two kinds of picture arrive: H.264 video, decoded by [VideoDecoder] onto [video] (a TextureView right
 * behind this view), or JPEG images, which this view draws itself. Either way this view does the camera
 * work and draws the cursor and messages on top.
 *
 * The image is zoomed in ([zoom] × "whole screen fits"), so only part of it is visible. That part
 * follows the mouse cursor: once the cursor comes within [EDGE_MARGIN] of an edge of the visible area,
 * the image slides along with it. At the real edges of the screen the image stops and the cursor
 * goes all the way to the edge.
 */
class ScreenView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 1 = the whole screen fits; 2 = twice as big (a quarter of the screen visible), … */
    var zoom = 2f
        set(v) { field = v.coerceIn(MIN_ZOOM, MAX_ZOOM); invalidate() }

    /** Status shown on top (connecting, errors); null = none. */
    var message: String? = null
        set(v) { field = v; invalidate() }

    /** Where H.264 video is shown (set by the remote screen). */
    var video: TextureView? = null

    /** Called (any thread) when the video decoder needs a keyframe: it just started or recovered from an error. */
    var requestKeyframe: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    // JPEG: triple buffering. The stream thread decodes into [spare], hands it over as [pending];
    // onDraw swaps [pending] into [current]. Bitmaps are reused, so no per-frame allocation.
    private val lock = Any()
    private var current: Bitmap? = null
    private var pending: Bitmap? = null
    private var spare: Bitmap? = null
    private var pendingFresh = false
    private val pendingRect = IntArray(4)
    private val rect = IntArray(4) // screen rect of the picture, in cursor coordinates

    // H.264: decoder on the video surface. [videoSize]/[videoRect] are what the computer last announced.
    private val videoLock = Any()
    private var surface: Surface? = null
    private var decoder: VideoDecoder? = null
    @Volatile private var videoMode = false
    @Volatile private var videoShown = false // a keyframe went into the current decoder
    private val videoSize = IntArray(2)
    private val videoRect = IntArray(4)
    private var lastKeyRequest = 0L
    private val videoMatrix = Matrix()
    private val appliedMatrix = Matrix()

    @Volatile private var cursorX = 0
    @Volatile private var cursorY = 0
    @Volatile private var hasCursor = false

    // Visible part of the image (top-left, image pixels): where it should be and where it's drawn now.
    private var targetX = 0f
    private var targetY = 0f
    private var shownX = 0f
    private var shownY = 0f
    private var lastDrawNanos = 0L
    private var jump = true

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF3"); textSize = 14f * density; textAlign = Paint.Align.CENTER
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC0D1117") }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.3f * density; strokeJoin = Paint.Join.ROUND
    }
    private val cursorPath = Path().apply {
        // Classic arrow pointer, tip at (0,0), in dp.
        val d = density
        moveTo(0f, 0f); lineTo(0f, 17f * d); lineTo(4.2f * d, 13.2f * d); lineTo(7f * d, 19.4f * d)
        lineTo(9.8f * d, 18.2f * d); lineTo(7.1f * d, 12.2f * d); lineTo(12.6f * d, 12.2f * d); close()
    }
    private val pill = RectF()

    /** Stream thread: a frame from [MediaStream]. */
    fun onMediaFrame(type: Int, data: ByteArray, length: Int) {
        when (type) {
            Protocol.CURSOR_POS -> {
                val b = ByteBuffer.wrap(data, 0, length)
                cursorX = b.int
                cursorY = b.int
                hasCursor = true
                postInvalidateOnAnimation()
            }
            Protocol.SCREEN_FRAME -> {
                if (videoMode) {
                    videoMode = false
                    releaseDecoder()
                }
                val b = ByteBuffer.wrap(data, 0, length)
                val r = IntArray(4) { b.int }
                val bmp = decode(data, 16, length - 16) ?: return
                synchronized(lock) {
                    spare = pending // a frame that was never shown gets overwritten next time
                    pending = bmp
                    r.copyInto(pendingRect)
                    pendingFresh = true
                }
                postInvalidateOnAnimation()
            }
            Protocol.VIDEO_FORMAT -> {
                val b = ByteBuffer.wrap(data, 0, length)
                val r = IntArray(4) { b.int }
                val w = b.int
                val h = b.int
                synchronized(videoLock) {
                    if (w != videoSize[0] || h != videoSize[1]) {
                        releaseDecoderLocked() // new size: new decoder, from the next keyframe
                        videoSize[0] = w
                        videoSize[1] = h
                    }
                    r.copyInto(videoRect)
                }
                synchronized(lock) { current = null; pending = null; spare = null; pendingFresh = false }
                videoMode = true
                jump = true
                postInvalidateOnAnimation()
            }
            Protocol.VIDEO_FRAME -> if (length > 1) decodeVideo(data, length)
        }
    }

    private fun decodeVideo(data: ByteArray, length: Int) {
        val key = (data[0].toInt() and 1) != 0
        synchronized(videoLock) {
            var d = decoder
            if (d == null || d.failed) {
                releaseDecoderLocked()
                val s = surface
                if (s == null || videoSize[0] == 0) return // not visible yet: a keyframe is asked for when it is
                d = runCatching { VideoDecoder(s, videoSize[0], videoSize[1]) { onDecoderError() } }.getOrNull() ?: return
                decoder = d
            }
            if (!videoShown && !key) {
                askForKeyframe() // a fresh decoder can't start from an in-between frame
                return
            }
            try {
                d.decode(data, 1, length - 1)
                if (key) videoShown = true
            } catch (e: IllegalStateException) {
                releaseDecoderLocked()
                askForKeyframe()
            }
        }
        postInvalidateOnAnimation()
    }

    private fun onDecoderError() {
        synchronized(videoLock) { videoShown = false }
        askForKeyframe()
    }

    private fun askForKeyframe() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyRequest < 500) return
        lastKeyRequest = now
        requestKeyframe?.invoke()
    }

    /** Main thread: the video surface appeared (or went away, with null). */
    fun setSurface(s: Surface?) {
        synchronized(videoLock) {
            releaseDecoderLocked()
            surface?.release()
            surface = s
        }
        // A still screen sends nothing more: ask for a picture right away.
        if (s != null && videoMode) askForKeyframe()
    }

    private fun releaseDecoder() = synchronized(videoLock) { releaseDecoderLocked() }

    private fun releaseDecoderLocked() {
        decoder?.release()
        decoder = null
        videoShown = false
    }

    private fun decode(data: ByteArray, offset: Int, length: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = spare
        }
        return try {
            BitmapFactory.decodeByteArray(data, offset, length, opts)
        } catch (e: IllegalArgumentException) {
            // The screen size changed and the old bitmap doesn't fit: decode into a new one.
            runCatching {
                BitmapFactory.decodeByteArray(data, offset, length, BitmapFactory.Options().apply { inMutable = true })
            }.getOrNull()
        }
    }

    /** Main thread: forget the picture (screen view turned off). */
    fun clear() {
        synchronized(lock) {
            current = null; pending = null; spare = null; pendingFresh = false
        }
        synchronized(videoLock) {
            releaseDecoderLocked()
            videoSize.fill(0)
        }
        videoMode = false
        hasCursor = false
        jump = true
        message = null
    }

    override fun onDraw(canvas: Canvas) {
        val bmp: Bitmap?
        synchronized(lock) {
            if (pendingFresh) {
                val old = current
                current = pending
                pending = old
                pendingFresh = false
                if (!pendingRect.contentEquals(rect)) jump = true // other monitor / resolution: no slide
                pendingRect.copyInto(rect)
            }
            bmp = current
        }
        val video = videoMode
        val iw: Float
        val ih: Float
        if (video) {
            synchronized(videoLock) {
                iw = videoSize[0].toFloat()
                ih = videoSize[1].toFloat()
                videoRect.copyInto(rect)
            }
        } else {
            iw = bmp?.width?.toFloat() ?: 0f
            ih = bmp?.height?.toFloat() ?: 0f
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (iw <= 0f || ih <= 0f || (video && !videoShown)) {
            drawMessage(canvas, message ?: "Connecting to the computer screen…", h / 2f)
            if (video) postInvalidateDelayed(100) // until the first video frame shows
            return
        }

        val scale = min(w / iw, h / ih) * zoom
        val vw = w / scale // visible part, in image pixels
        val vh = h / scale

        // Cursor in image pixels (the image can have more pixels than cursor units, e.g. Retina).
        val px = if (hasCursor && rect[2] > 0) ((cursorX - rect[0]) * iw / rect[2]).coerceIn(0f, iw) else iw / 2f
        val py = if (hasCursor && rect[3] > 0) ((cursorY - rect[1]) * ih / rect[3]).coerceIn(0f, ih) else ih / 2f
        val now = System.nanoTime()
        if (jump || lastDrawNanos == 0L) {
            // New picture (first frame, other monitor): start centered on the cursor.
            targetX = px - vw / 2f
            targetY = py - vh / 2f
        }
        targetX = follow(targetX, px, vw, iw)
        targetY = follow(targetY, py, vh, ih)

        if (jump || lastDrawNanos == 0L) {
            shownX = targetX; shownY = targetY
            jump = false
        } else {
            // Ease towards the target (~50 ms), so jumps of the cursor don't jerk the picture.
            val dt = ((now - lastDrawNanos) / 1e9f).coerceIn(0f, 0.1f)
            val k = 1f - exp(-dt / 0.05f)
            shownX += (targetX - shownX) * k
            shownY += (targetY - shownY) * k
        }
        lastDrawNanos = now
        val settling = abs(targetX - shownX) * scale > 0.5f || abs(targetY - shownY) * scale > 0.5f
        if (!settling) { shownX = targetX; shownY = targetY }

        if (video) {
            // The TextureView stretches the video over its whole area; map it to the zoomed, panned picture.
            videoMatrix.setScale(iw * scale / w, ih * scale / h)
            videoMatrix.postTranslate(-shownX * scale, -shownY * scale)
            if (videoMatrix != appliedMatrix) {
                appliedMatrix.set(videoMatrix)
                // setTransform alone only takes effect with the next video frame, and a still screen sends
                // none: redraw the TextureView so zooming and panning show right away.
                this.video?.apply { setTransform(videoMatrix); invalidate() }
            }
        } else if (bmp != null) {
            canvas.save()
            canvas.scale(scale, scale)
            canvas.translate(-shownX, -shownY)
            canvas.drawBitmap(bmp, 0f, 0f, imagePaint)
            canvas.restore()
        }

        if (hasCursor) {
            canvas.save()
            canvas.translate((px - shownX) * scale, (py - shownY) * scale)
            canvas.drawPath(cursorPath, cursorFill)
            canvas.drawPath(cursorPath, cursorStroke)
            canvas.restore()
        }
        message?.let { drawMessage(canvas, it, h - 28f * density) }
        if (settling) postInvalidateOnAnimation()
    }

    /** Edge-push camera on one axis: returns the new start of the visible range. */
    private fun follow(start: Float, cursor: Float, visible: Float, total: Float): Float {
        if (total <= visible) return (total - visible) / 2f // everything fits: center it
        val margin = visible * EDGE_MARGIN
        var s = start
        if (cursor < s + margin) s = cursor - margin
        if (cursor > s + visible - margin) s = cursor - visible + margin
        return s.coerceIn(0f, total - visible)
    }

    private fun drawMessage(canvas: Canvas, text: String, y: Float) {
        val tw = messagePaint.measureText(text)
        val pad = 12f * density
        pill.set(width / 2f - tw / 2f - pad, y - 20f * density, width / 2f + tw / 2f + pad, y + 10f * density)
        canvas.drawRoundRect(pill, 14f * density, 14f * density, pillPaint)
        canvas.drawText(text, width / 2f, y, messagePaint)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        private const val EDGE_MARGIN = 0.2f
    }
}
