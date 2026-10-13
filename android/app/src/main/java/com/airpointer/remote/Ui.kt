package com.airpointer.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan

/** Small UI helpers shared by the screens. */
object Ui {
    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    /**
     * A vector icon inline with text ("[icon]  Keyboard"), tinted [color]: for buttons and labels, so the
     * app never relies on emoji. Empty [text] gives the icon alone.
     */
    fun iconText(context: Context, icon: Int, text: CharSequence, color: Int, sizeDp: Float = 18f): CharSequence {
        val d = context.getDrawable(icon)!!.mutate()
        val px = dp(context, sizeDp)
        d.setBounds(0, 0, px, px)
        d.setTint(color)
        return SpannableStringBuilder().apply {
            append(" ", CenteredIconSpan(d), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (text.isNotEmpty()) append("  ").append(text)
        }
    }

    /** Draws an icon centered on the text's letters (ImageSpan centers on the line, which looks low/high). */
    private class CenteredIconSpan(private val d: Drawable) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                val p = paint.fontMetricsInt
                val h = d.bounds.height()
                val center = (p.ascent + p.descent) / 2
                it.ascent = minOf(p.ascent, center - h / 2)
                it.top = minOf(p.top, it.ascent)
                it.descent = maxOf(p.descent, center + h / 2)
                it.bottom = maxOf(p.bottom, it.descent)
            }
            return d.bounds.width()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = paint.fontMetricsInt
            val center = y + (p.ascent + p.descent) / 2f
            canvas.save()
            canvas.translate(x, center - d.bounds.height() / 2f)
            d.draw(canvas)
            canvas.restore()
        }
    }

    /** Fills in view_top_bar.xml: back closes the screen. Returns the subtitle chip (hidden until given text). */
    fun topBar(activity: android.app.Activity, title: String): android.widget.TextView {
        activity.findViewById<android.widget.TextView>(R.id.barTitle).text = title
        activity.findViewById<android.view.View>(R.id.back).setOnClickListener { activity.finish() }
        return activity.findViewById(R.id.barSubtitle)
    }

    /** A shortcut button's face: its icon (if any) and label. */
    fun shortcutFace(context: Context, s: Shortcut, color: Int): CharSequence {
        val icon = Icons.of(context, s.icon) ?: return s.label.ifEmpty { s.title }
        return iconText(context, icon, s.label, color, 18f)
    }
}
