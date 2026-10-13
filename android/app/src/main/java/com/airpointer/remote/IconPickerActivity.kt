package com.airpointer.remote

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Picks a line icon for a shortcut button from the full Lucide set; search matches names and tags. */
class IconPickerActivity : Activity() {

    private var shown: List<Icons.Info> = emptyList()
    private var current: String? = null
    private lateinit var count: TextView
    private val adapter = IconAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_picker)
        current = intent.getStringExtra(EXTRA_ICON)
        Ui.topBar(this, "Choose an icon")
        count = findViewById(R.id.count)
        val grid = findViewById<GridView>(R.id.grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ICON, shown[position].name))
            finish()
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            Toast.makeText(this, shown[position].name.replace('-', ' '), Toast.LENGTH_SHORT).show()
            true
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString() ?: "")
        })
        filter("")
        // Start at the current icon.
        shown.indexOfFirst { it.name == current }.takeIf { it >= 0 }?.let { grid.setSelection(it) }
    }

    private fun filter(query: String) {
        shown = Icons.search(this, query)
        count.text = when {
            query.isBlank() -> "${shown.size} icons"
            shown.isEmpty() -> "No icons match \"$query\"."
            else -> "${shown.size} icons"
        }
        adapter.notifyDataSetChanged()
    }

    private inner class IconAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? FrameLayout ?: FrameLayout(this@IconPickerActivity).apply {
                setBackgroundResource(R.drawable.bg_icon_cell)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 56f))
                addView(ImageView(context).apply {
                    imageTintList = ColorStateList.valueOf(getColor(R.color.text))
                }, FrameLayout.LayoutParams(Ui.dp(context, 26f), Ui.dp(context, 26f), Gravity.CENTER))
            }
            val info = shown[position]
            val image = cell.getChildAt(0) as ImageView
            Icons.of(this@IconPickerActivity, info.name)?.let { image.setImageResource(it) }
            image.contentDescription = info.name
            cell.isSelected = info.name == current
            return cell
        }
    }

    companion object {
        const val EXTRA_ICON = "icon"
    }
}
