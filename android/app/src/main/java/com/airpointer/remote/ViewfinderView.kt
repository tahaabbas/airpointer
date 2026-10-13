package com.airpointer.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** QR scanner overlay: dims everything but a rounded square, framed by accent corner brackets. */
class ViewfinderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_light)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val frame = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height) * 0.68f
        val left = (width - size) / 2f
        val top = (height - size) / 2f - 24f * density
        frame.set(left, top, left + size, top + size)
        val r = 24f * density

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(frame, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)

        val len = size * 0.16f
        path.reset()
        // top-left, top-right, bottom-right, bottom-left brackets, each with a rounded corner
        path.moveTo(frame.left, frame.top + len); path.lineTo(frame.left, frame.top + r)
        path.quadTo(frame.left, frame.top, frame.left + r, frame.top); path.lineTo(frame.left + len, frame.top)
        path.moveTo(frame.right - len, frame.top); path.lineTo(frame.right - r, frame.top)
        path.quadTo(frame.right, frame.top, frame.right, frame.top + r); path.lineTo(frame.right, frame.top + len)
        path.moveTo(frame.right, frame.bottom - len); path.lineTo(frame.right, frame.bottom - r)
        path.quadTo(frame.right, frame.bottom, frame.right - r, frame.bottom); path.lineTo(frame.right - len, frame.bottom)
        path.moveTo(frame.left + len, frame.bottom); path.lineTo(frame.left + r, frame.bottom)
        path.quadTo(frame.left, frame.bottom, frame.left, frame.bottom - r); path.lineTo(frame.left, frame.bottom - len)
        canvas.drawPath(path, corner)
    }
}
