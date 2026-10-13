package com.airpointer.remote

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible view that receives the phone keyboard's input and forwards it immediately
 * ("live typing"): characters as they are typed, Backspace, and Enter. Nothing is shown on the phone.
 */
class KeyCaptureView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onText: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onEnter: () -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // "Visible password" stops most keyboards from holding letters back as suggestions,
        // so every key press is delivered right away.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this@KeyCaptureView, false) {
        /** Text the keyboard is still composing (already sent to the computer). */
        private var composing = ""

        /** Sends only the difference between what was sent and the new text. */
        private fun replaceComposing(newText: String) {
            val common = composing.commonPrefixWith(newText).length
            repeat(composing.length - common) { onBackspace() }
            emit(newText.substring(common))
            composing = newText
        }

        private fun emit(s: String) {
            if (s.isEmpty()) return
            val parts = s.split('\n')
            parts.forEachIndexed { i, part ->
                if (part.isNotEmpty()) onText(part)
                if (i < parts.size - 1) onEnter()
            }
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            composing = composing.dropLast(minOf(beforeLength, composing.length))
            repeat(beforeLength) { onBackspace() }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    composing = composing.dropLast(1)
                    onBackspace()
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    composing = ""
                    onEnter()
                }
                else -> {
                    val c = event.unicodeChar
                    if (c != 0) onText(String(Character.toChars(c)))
                }
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            composing = ""
            onEnter()
            return true
        }
    }
}
