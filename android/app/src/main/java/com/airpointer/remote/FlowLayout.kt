package com.airpointer.remote

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Lays children out left to right and wraps onto new lines (used for the landscape shortcut area). */
class FlowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    var horizontalGap = (6 * density).toInt()
    var verticalGap = (6 * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(
                child,
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            if (x > 0 && x + child.measuredWidth > available) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            x += child.measuredWidth + horizontalGap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        val contentHeight = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(contentHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > available) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            val left = paddingLeft + x
            val top = paddingTop + y
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += child.measuredWidth + horizontalGap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
