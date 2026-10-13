package com.airpointer.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

/**
 * Creates or edits one shortcut button of a computer's bar. The button needs an icon, a label, or both,
 * and a valid action: keys ("Alt+P", "Ctrl+C, Ctrl+V") or text to type.
 */
class EditShortcutActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var profile: Link.Profile
    private lateinit var items: MutableList<Shortcut>
    private var index = -1

    private var icon: String? = null
    private var textMode = false

    private lateinit var preview: Button
    private lateinit var iconPreview: ImageView
    private lateinit var iconName: TextView
    private lateinit var iconClear: View
    private lateinit var label: EditText
    private lateinit var modeKeys: TextView
    private lateinit var modeText: TextView
    private lateinit var value: EditText
    private lateinit var pressEnter: CheckBox
    private lateinit var feedback: TextView
    private lateinit var help: TextView
    private lateinit var save: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_shortcut)
        prefs = Prefs(this)
        @Suppress("DEPRECATION")
        profile = intent.getSerializableExtra(ShortcutsActivity.EXTRA_PROFILE) as? Link.Profile ?: Link.Profile.GENERIC
        items = prefs.shortcutsFor(profile).toMutableList()
        index = intent.getIntExtra(EXTRA_INDEX, -1).takeIf { it in items.indices } ?: -1
        val existing = items.getOrNull(index)

        Ui.topBar(this, if (existing == null) "New button" else "Edit button").apply {
            text = "For ${profile.name}"
            visibility = View.VISIBLE
            isClickable = false
        }
        preview = findViewById(R.id.preview)
        iconPreview = findViewById(R.id.iconPreview)
        iconName = findViewById(R.id.iconName)
        iconClear = findViewById(R.id.iconClear)
        label = findViewById(R.id.label)
        modeKeys = findViewById(R.id.modeKeys)
        modeText = findViewById(R.id.modeText)
        value = findViewById(R.id.value)
        pressEnter = findViewById(R.id.pressEnter)
        feedback = findViewById(R.id.feedback)
        help = findViewById(R.id.help)
        save = findViewById(R.id.save)

        icon = savedInstanceState?.getString(STATE_ICON) ?: existing?.icon
        textMode = savedInstanceState?.getBoolean(STATE_TEXT) ?: (existing?.isText == true)
        if (savedInstanceState == null) {
            label.setText(existing?.label ?: "")
            value.setText(existing?.combo ?: "")
            pressEnter.isChecked = existing?.pressEnter == true
        }

        modeKeys.text = Ui.iconText(this, R.drawable.lic_keyboard, "Press keys", getColor(R.color.text), 16f)
        modeText.text = Ui.iconText(this, R.drawable.lic_type, "Type text", getColor(R.color.text), 16f)
        modeKeys.setOnClickListener { textMode = false; applyMode() }
        modeText.setOnClickListener { textMode = true; applyMode() }
        @Suppress("DEPRECATION")
        findViewById<View>(R.id.iconRow).setOnClickListener {
            startActivityForResult(Intent(this, IconPickerActivity::class.java).putExtra(IconPickerActivity.EXTRA_ICON, icon), REQ_ICON)
        }
        iconClear.setOnClickListener { icon = null; refresh() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        label.addTextChangedListener(watcher)
        value.addTextChangedListener(watcher)
        pressEnter.setOnCheckedChangeListener { _, _ -> refresh() }
        save.setOnClickListener { saveAndClose() }
        applyMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ICON, icon)
        outState.putBoolean(STATE_TEXT, textMode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ICON && resultCode == RESULT_OK) {
            icon = data?.getStringExtra(IconPickerActivity.EXTRA_ICON)
            refresh()
        }
    }

    private fun applyMode() {
        modeKeys.setBackgroundResource(if (textMode) R.drawable.bg_segment_off else R.drawable.bg_segment_on)
        modeText.setBackgroundResource(if (textMode) R.drawable.bg_segment_on else R.drawable.bg_segment_off)
        if (textMode) {
            value.hint = "Text, e.g. sudo apt update"
            value.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            value.maxLines = 4
            pressEnter.visibility = View.VISIBLE
            help.text = "Types this text exactly as written."
        } else {
            value.hint = "Keys, e.g. Alt+P   or   Ctrl+C, Ctrl+V"
            value.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            value.maxLines = 1
            pressEnter.visibility = View.GONE
            help.text = "+  keys together:  Ctrl+C\n" +
                ",  one after another:  Ctrl+C, Ctrl+V"
        }
        refresh()
    }

    /** The button as it would be saved, or null with the reason shown. */
    private fun draft(): Shortcut? {
        val raw = value.text.toString()
        val combo: String? = if (textMode) raw.takeIf { it.isNotEmpty() } else KeyCombo.parseSequence(raw).normalized
        val text = label.text.toString().trim()
        val problem = when {
            icon == null && text.isEmpty() -> "Choose an icon or type a label."
            combo == null && textMode -> "Enter the text to type."
            combo == null -> KeyCombo.parseSequence(raw).error ?: "Enter the keys to press."
            else -> null
        }
        val ok = problem == null
        feedback.text = Ui.iconText(
            this,
            if (ok) R.drawable.lic_check else R.drawable.lic_circle_alert,
            if (ok) (if (textMode) "Types this text${if (pressEnter.isChecked) ", then Enter" else ""}" else "Sends $combo") else problem!!,
            getColor(if (ok) R.color.ok else R.color.text_muted),
            16f,
        )
        feedback.setTextColor(getColor(if (ok) R.color.ok else R.color.text_muted))
        return if (ok) Shortcut(text, combo!!, isText = textMode, pressEnter = textMode && pressEnter.isChecked, icon = icon) else null
    }

    private fun refresh() {
        val res = Icons.of(this, icon)
        iconPreview.setImageResource(res ?: R.drawable.lic_image)
        iconPreview.alpha = if (res != null) 1f else 0.45f
        iconName.text = icon?.replace('-', ' ') ?: "None. Tap to choose from ${Icons.all(this).size} icons"
        iconClear.visibility = if (icon != null) View.VISIBLE else View.GONE

        val d = draft()
        save.isEnabled = d != null
        val text = label.text.toString().trim()
        preview.text = when {
            res != null -> Ui.iconText(this, res, text, getColor(R.color.text))
            text.isNotEmpty() -> text
            else -> "Button"
        }
        preview.alpha = if (res != null || text.isNotEmpty()) 1f else 0.4f
    }

    private fun saveAndClose() {
        val s = draft() ?: return
        // Re-read: the list may have changed meanwhile (e.g. reordered in another screen).
        items = prefs.shortcutsFor(profile).toMutableList()
        if (index in items.indices) items[index] = s else items.add(s)
        prefs.saveShortcuts(profile, items)
        finish()
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val REQ_ICON = 1
        private const val STATE_ICON = "icon"
        private const val STATE_TEXT = "text"
    }
}
