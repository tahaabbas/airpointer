package com.airpointer.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The shortcut bar of one computer ([Link.Profile]): live preview, new / preset buttons, and a list where
 * buttons are moved by dragging their handle, edited, or removed (with undo).
 */
class ShortcutsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var profile: Link.Profile
    private var items = mutableListOf<Shortcut>()

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var previewBar: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var empty: View
    private lateinit var chip: TextView
    private lateinit var undoBar: View
    private lateinit var undoText: TextView
    private var removed: Pair<Int, Shortcut>? = null
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; removed = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcuts)
        prefs = Prefs(this)
        @Suppress("DEPRECATION")
        profile = intent.getSerializableExtra(EXTRA_PROFILE) as? Link.Profile ?: defaultProfile()

        chip = Ui.topBar(this, "Shortcut buttons")
        chip.setOnClickListener { chooseProfile() }
        scroll = findViewById(R.id.scroll)
        list = findViewById(R.id.list)
        previewBar = findViewById(R.id.previewBar)
        countLabel = findViewById(R.id.countLabel)
        empty = findViewById(R.id.empty)
        undoBar = findViewById(R.id.undoBar)
        undoText = findViewById(R.id.undoText)

        findViewById<Button>(R.id.btnNew).apply {
            text = Ui.iconText(context, R.drawable.lic_plus, "New button", 0xFFFFFFFF.toInt())
            setOnClickListener { edit(-1) }
        }
        findViewById<Button>(R.id.btnPresets).apply {
            text = Ui.iconText(context, R.drawable.lic_layout_grid, "Presets", getColor(R.color.text))
            setOnClickListener { presetsDialog() }
        }
        findViewById<TextView>(R.id.btnReset).apply {
            text = Ui.iconText(context, R.drawable.lic_rotate_ccw, "Reset", getColor(R.color.accent_light), 14f)
            setOnClickListener { confirmReset() }
        }
        findViewById<View>(R.id.undo).setOnClickListener {
            removed?.let { (i, s) -> items.add(i.coerceAtMost(items.size), s); save() }
            hideUndo.run()
        }
    }

    override fun onResume() {
        super.onResume()
        showProfile()
        items = prefs.shortcutsFor(profile).toMutableList() // picks up changes made in the editor
        render()
    }

    /** While connected: that computer. Otherwise the first paired one. */
    private fun defaultProfile(): Link.Profile =
        if (Link.state != Link.State.DISCONNECTED) Link.profile
        else PcStore.all(this).firstOrNull()?.let { Link.Profile.of(it) } ?: Link.Profile.GENERIC

    private fun showProfile() {
        val switchable = profiles().size > 1
        chip.visibility = View.VISIBLE
        chip.text = if (switchable) {
            SpannableStringBuilder("For ${profile.name}  ").append(
                Ui.iconText(this, R.drawable.lic_chevron_down, "", getColor(R.color.accent_light), 14f),
            )
        } else {
            "For ${profile.name}"
        }
        chip.isClickable = switchable
    }

    private fun profiles(): List<Link.Profile> = PcStore.all(this).map { Link.Profile.of(it) } + Link.Profile.GENERIC

    private fun chooseProfile() {
        val choices = profiles()
        AlertDialog.Builder(this)
            .setTitle("Buttons for")
            .setItems(choices.map { it.name }.toTypedArray()) { _, i ->
                profile = choices[i]
                onResume()
            }
            .show()
    }

    private fun save() {
        prefs.saveShortcuts(profile, items.toList())
        render()
    }

    private fun edit(index: Int) {
        startActivity(
            Intent(this, EditShortcutActivity::class.java)
                .putExtra(EXTRA_PROFILE, profile)
                .putExtra(EditShortcutActivity.EXTRA_INDEX, index),
        )
    }

    // ---------- list ----------

    private fun render() {
        val keepScroll = scroll.scrollY // rebuilding the rows would otherwise jump back to the top
        list.removeAllViews()
        previewBar.removeAllViews()
        countLabel.text = if (items.isEmpty()) "BUTTONS" else "BUTTONS · ${items.size}"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        items.forEachIndexed { i, s ->
            val row = inflater.inflate(R.layout.item_shortcut, list, false)
            val icon = Icons.of(this, s.icon)
            row.findViewById<ImageView>(R.id.icon).apply {
                visibility = if (icon != null) View.VISIBLE else View.GONE
                icon?.let { setImageResource(it) }
            }
            row.findViewById<View>(R.id.iconText).visibility = if (icon == null) View.VISIBLE else View.GONE
            row.findViewById<TextView>(R.id.title).text = s.title
            row.findViewById<TextView>(R.id.summary).text = s.summary
            row.findViewById<View>(R.id.edit).setOnClickListener { edit(i) }
            row.findViewById<View>(R.id.delete).setOnClickListener { remove(i) }
            row.setOnClickListener { edit(i) }
            enableDrag(row.findViewById(R.id.handle), row, i)
            list.addView(row)

            previewBar.addView(Button(this, null, 0, R.style.ShortcutBtn).apply {
                text = Ui.shortcutFace(context, s, getColor(R.color.text))
                contentDescription = s.title
                isClickable = false
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44f)).apply {
                marginEnd = Ui.dp(this@ShortcutsActivity, 6f)
            })
        }
        scroll.post { scroll.scrollTo(0, keepScroll) }
    }

    private fun remove(i: Int) {
        val s = items.removeAt(i)
        removed = i to s
        save()
        undoText.text = "Removed ${s.title}"
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(hideUndo)
        undoBar.postDelayed(hideUndo, 5000)
    }

    // ---------- drag to reorder ----------

    private class Drag(val row: View, val from: Int, val startY: Float, val startScroll: Int, val step: Float) {
        var target = from
        var y = startY
    }

    private var drag: Drag? = null

    /** Keeps scrolling while the dragged row is held near the top or bottom edge. */
    private val autoScroll = object : Runnable {
        override fun run() {
            val d = drag ?: return
            val loc = IntArray(2)
            scroll.getLocationOnScreen(loc)
            val edge = Ui.dp(this@ShortcutsActivity, 64f)
            val speed = Ui.dp(this@ShortcutsActivity, 9f)
            val moved = Ui.dp(this@ShortcutsActivity, 24f)
            // Only once the finger has moved towards that edge: a row picked up near an edge must not run off.
            val dy = when {
                d.y < loc[1] + edge && d.y < d.startY - moved -> -speed
                d.y > loc[1] + scroll.height - edge && d.y > d.startY + moved -> speed
                else -> 0
            }
            if (dy != 0 && scroll.canScrollVertically(dy)) {
                scroll.scrollBy(0, dy)
                updateDrag()
            }
            scroll.postOnAnimation(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View, row: View, index: Int) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = row.layoutParams as LinearLayout.LayoutParams
                    drag = Drag(row, index, e.rawY, scroll.scrollY, (row.height + lp.topMargin).toFloat())
                    scroll.requestDisallowInterceptTouchEvent(true)
                    row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    row.animate().scaleX(1.03f).scaleY(1.03f).translationZ(Ui.dp(this, 10f).toFloat()).setDuration(120).start()
                    scroll.postOnAnimation(autoScroll)
                }
                MotionEvent.ACTION_MOVE -> {
                    drag?.y = e.rawY
                    updateDrag()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
            }
            true
        }
    }

    private fun updateDrag() {
        val d = drag ?: return
        val dy = (d.y - d.startY) + (scroll.scrollY - d.startScroll)
        d.row.translationY = dy
        val target = (d.from + (dy / d.step).roundToInt()).coerceIn(0, list.childCount - 1)
        if (target == d.target) return
        d.target = target
        for (j in 0 until list.childCount) {
            if (j == d.from) continue
            val shift = when {
                j in (d.from + 1)..target -> -d.step
                j in target until d.from -> d.step
                else -> 0f
            }
            list.getChildAt(j).animate().translationY(shift).setDuration(120).start()
        }
    }

    private fun endDrag() {
        val d = drag ?: return
        drag = null
        scroll.removeCallbacks(autoScroll)
        if (d.target != d.from) {
            items.add(d.target, items.removeAt(d.from))
            save() // new rows, no leftover offsets
        } else {
            d.row.animate().translationY(0f).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
        }
    }

    // ---------- presets / reset ----------

    /** Ready-made buttons (for this computer's OS) that aren't on the bar yet. */
    private fun presetsDialog() {
        val available = ShortcutPresets.presetsFor(profile.os).flatMap { (category, presets) ->
            presets.filter { p -> items.none { it.combo == p.combo && it.icon == p.icon && it.label == p.label } }
                .map { category to it }
        }
        if (available.isEmpty()) {
            AlertDialog.Builder(this).setMessage("All presets are already on the bar.").setPositiveButton("OK", null).show()
            return
        }
        val labels = available.map { (category, p) ->
            SpannableStringBuilder(Ui.shortcutFace(this, p, getColor(R.color.text))).append("    $category · ${p.title}")
        }.toTypedArray<CharSequence>()
        val checked = BooleanArray(labels.size)
        AlertDialog.Builder(this)
            .setTitle("Add preset buttons")
            .setMultiChoiceItems(labels, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Add") { _, _ ->
                available.forEachIndexed { i, (_, p) -> if (checked[i]) items.add(p) }
                save()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset the buttons for ${profile.name}?")
            .setMessage("Your own buttons will be removed and the default set restored.")
            .setPositiveButton("Reset") { _, _ ->
                items = ShortcutPresets.defaultsFor(profile.os).toMutableList()
                save()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
