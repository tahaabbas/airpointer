package com.airpointer.remote

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** AirPointer's license and the third-party notices (assets copied in by the build, see app/build.gradle.kts). */
class LicensesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = listOf("LICENSE.txt", "THIRD-PARTY-NOTICES.txt").joinToString("\n\n") { name ->
            runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
        }.replace("\r\n", "\n")
        // The notices are about 1 MB: a list of paragraphs scrolls smoothly where one huge TextView would not.
        val paragraphs = all.split("\n\n").filter { it.isNotBlank() }
        val pad = (16 * resources.displayMetrics.density).toInt()

        val list = ListView(this).apply {
            divider = null
            setPadding(pad, 0, pad, pad)
            clipToPadding = false
            adapter = object : BaseAdapter() {
                override fun getCount() = paragraphs.size
                override fun getItem(position: Int) = paragraphs[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    ((convertView as? TextView) ?: TextView(this@LicensesActivity).apply {
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 12f
                        setTextIsSelectable(true)
                        setPadding(0, pad / 2, 0, pad / 2)
                    }).apply { text = paragraphs[position] }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(layoutInflater.inflate(R.layout.view_top_bar, this, false))
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        Ui.topBar(this, "Open-source licenses")
    }
}
