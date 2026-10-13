package com.airpointer.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.Button

/**
 * Mouse button that shows a left-to-right progress bar while held. When the bar completes the
 * button "latches" (stays pressed on the computer) and turns green until it is tapped again.
 * The bar's duration is the "Hold LEFT to lock" setting (RemoteActivity drives [progress]).
 * The touch logic lives in RemoteActivity; this view only draws the state.
 */
class HoldButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {

    private val radius = 12f * resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x882563EB.toInt() }
    private val clip = Path()

    /** 0..1 while the hold timer runs. */
    var progress = 0f
        set(v) {
            field = v
            invalidate()
        }

    var latched = false
        set(v) {
            field = v
            progress = 0f
            // Latched = green; released = back to the normal look. The label never changes.
            setBackgroundResource(if (v) R.drawable.bg_btn_on else R.drawable.bg_btn)
        }

    override fun onDraw(canvas: Canvas) {
        if (progress > 0f && !latched) {
            clip.reset()
            clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, width * progress, height.toFloat(), fill)
            canvas.restore()
        }
        super.onDraw(canvas) // label on top of the bar
    }
}
