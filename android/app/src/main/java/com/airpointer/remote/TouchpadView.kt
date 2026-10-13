package com.airpointer.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Laptop-style touchpad:
 *  - 1 finger move: move cursor        - tap: left click        - double tap: double click
 *  - long press (then move): drag      - 2 finger tap: right click
 *  - 2 finger drag: scroll             - pinch: zoom
 *  - 3 finger tap: middle click        - 3 finger swipe: up = task view, down = desktop, left/right = switch app
 *
 * In [screenMode] (screen view on) the pad turns translucent over the computer screen behind it, its hint
 * text is hidden, and pinching zooms that picture instead of the computer.
 */
class TouchpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onPadMove(dxDp: Float, dyDp: Float)
        fun onPadScroll(dyDp: Float, dxDp: Float)
        fun onPadClick(button: Int)
        fun onPadButton(button: Int, down: Boolean)
        fun onPadZoom(steps: Int)
        fun onPadSwipe3(action: Int)
        /** Pinch while in [screenMode]: multiply the screen picture's zoom by [factor]. */
        fun onPadViewZoom(factor: Float)
    }

    var listener: Listener? = null
    var haptics = true

    var screenMode = false
        set(v) { field = v; invalidate() }

    /** How much the pad covers the screen picture in [screenMode]: 0 = invisible, 1 = opaque. */
    var padOpacity = 0.35f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeout = 260L
    private val longPressTimeout = 450L

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    private var moved = false
    private var dragging = false

    // Multi-finger tracking
    private var multiMode = 0 // 0 undecided, 1 scroll, 2 zoom
    private var baseCx = 0f
    private var baseCy = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var startSpan = 0f
    private var lastSpan = 0f
    private var zoomAccum = 0f
    private var resetCentroid = false
    private var swipeDx = 0f
    private var swipeDy = 0f

    private val longPress = Runnable {
        if (!moved && maxPointers == 1) {
            dragging = true
            buzz(HapticFeedbackConstants.LONG_PRESS)
            listener?.onPadButton(Protocol.BTN_LEFT, true)
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C2128") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30363D"); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E7681"); textSize = 13f * density; textAlign = Paint.Align.CENTER
    }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#332563EB") }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C2128") }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = 18f * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (screenMode) {
            overlayPaint.alpha = (padOpacity * 255).toInt()
            canvas.drawRoundRect(rect, r, r, overlayPaint)
        } else {
            canvas.drawRoundRect(rect, r, r, bgPaint)
        }
        if (dragging) canvas.drawRoundRect(rect, r, r, dragPaint)
        rect.inset(borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, borderPaint)
        if (screenMode) return
        val cx = width / 2f
        var y = height / 2f - 30f * density
        for (line in HINTS) {
            canvas.drawText(line, cx, y, hintPaint)
            y += 20f * density
        }
    }

    private fun buzz(type: Int) {
        if (haptics) performHapticFeedback(type)
    }

    private fun centroid(e: MotionEvent, skipIndex: Int = -1): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skipIndex) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        return if (n == 0) 0f to 0f else (sx / n) to (sy / n)
    }

    private fun span(e: MotionEvent): Float =
        if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downTime = e.eventTime
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                maxPointers = 1
                moved = false
                dragging = false
                multiMode = 0
                swipeDx = 0f; swipeDy = 0f
                postDelayed(longPress, longPressTimeout)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                maxPointers = maxOf(maxPointers, e.pointerCount)
                val (cx, cy) = centroid(e)
                baseCx = cx; baseCy = cy; lastCx = cx; lastCy = cy
                startSpan = span(e); lastSpan = startSpan
                resetCentroid = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (maxPointers == 1 || dragging) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                        return true // start from here so the cursor doesn't jump by the slop distance
                    }
                    if (moved || dragging) listener?.onPadMove(dx / density, dy / density)
                } else {
                    val (cx, cy) = centroid(e)
                    if (resetCentroid) {
                        lastCx = cx; lastCy = cy; lastSpan = span(e); resetCentroid = false
                        return true
                    }
                    val dcx = cx - lastCx
                    val dcy = cy - lastCy
                    lastCx = cx; lastCy = cy
                    if (e.pointerCount == 2 && maxPointers == 2) handleTwoFinger(e, cx, cy, dcx, dcy)
                    else if (maxPointers >= 3) {
                        swipeDx += dcx; swipeDy += dcy
                        if (hypot(swipeDx, swipeDy) > slop) moved = true
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Centroid jumps when a finger lifts; re-baseline on the next move.
                resetCentroid = true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val quick = e.eventTime - downTime < tapTimeout
                when {
                    dragging -> {
                        dragging = false
                        listener?.onPadButton(Protocol.BTN_LEFT, false)
                        invalidate()
                    }
                    !moved && quick -> {
                        val button = when (maxPointers) {
                            1 -> Protocol.BTN_LEFT
                            2 -> Protocol.BTN_RIGHT
                            else -> Protocol.BTN_MIDDLE
                        }
                        buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener?.onPadClick(button)
                    }
                    maxPointers >= 3 && moved -> {
                        val min = 50f * density
                        val action = when {
                            abs(swipeDy) > abs(swipeDx) && swipeDy < -min -> Protocol.ACTION_TASK_VIEW
                            abs(swipeDy) > abs(swipeDx) && swipeDy > min -> Protocol.ACTION_SHOW_DESKTOP
                            swipeDx > min -> Protocol.ACTION_NEXT_APP
                            swipeDx < -min -> Protocol.ACTION_PREV_APP
                            else -> 0
                        }
                        if (action != 0) {
                            buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                            listener?.onPadSwipe3(action)
                        }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    listener?.onPadButton(Protocol.BTN_LEFT, false)
                }
            }
        }
        if (dragging) invalidate()
        return true
    }

    private fun handleTwoFinger(e: MotionEvent, cx: Float, cy: Float, dcx: Float, dcy: Float) {
        val sp = span(e)
        if (multiMode == 0) {
            val spanChange = abs(sp - startSpan)
            val panDist = hypot(cx - baseCx, cy - baseCy)
            if (spanChange > slop * 2.5f && spanChange > panDist) multiMode = 2
            else if (panDist > slop) multiMode = 1
            if (multiMode != 0) moved = true
        }
        when (multiMode) {
            1 -> listener?.onPadScroll(dcy / density, dcx / density)
            2 -> if (screenMode) {
                if (lastSpan > 0f && sp > 0f) listener?.onPadViewZoom(sp / lastSpan)
            } else {
                if (lastSpan > 0f && sp > 0f) zoomAccum += ln(sp / lastSpan)
                while (zoomAccum > 0.18f) { listener?.onPadZoom(1); zoomAccum -= 0.18f }
                while (zoomAccum < -0.18f) { listener?.onPadZoom(-1); zoomAccum += 0.18f }
            }
        }
        lastSpan = sp
    }

    companion object {
        private val HINTS = listOf(
            "Tap = click  ·  2-finger tap = right click",
            "Hold = drag  ·  2-finger drag = scroll",
            "Pinch = zoom  ·  3-finger swipe = switch apps",
        )
    }
}

/** Narrow strip at the side of the touchpad: drag up/down with one finger to scroll. */
class ScrollStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onScroll: ((dyDp: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var lastY = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161B22") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#484F58"); strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = 14f * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        val cx = width / 2f
        val w = width * 0.25f
        var y = height * 0.1f
        while (y < height * 0.9f) {
            canvas.drawLine(cx - w, y, cx + w, y, line)
            y += 14f * density
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                onScroll?.invoke((e.y - lastY) / density)
                lastY = e.y
            }
        }
        return true
    }
}
