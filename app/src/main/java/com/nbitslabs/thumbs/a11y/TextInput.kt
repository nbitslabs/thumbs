package com.nbitslabs.thumbs.a11y

import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.nbitslabs.thumbs.mcp.ToolException

enum class TypeMethod { AUTO, IME, SET_TEXT, PASTE }

/**
 * Text entry and key events.
 *
 * On Android 13+ the service acts as an input method for the focused field
 * (flagInputMethodEditor), which gives real IME semantics: unicode and emoji, cursor-relative
 * insertion, key events with modifiers, and it works in WebViews and terminals that ignore
 * ACTION_SET_TEXT. Older versions, or fields the IME connection can't reach, fall back to
 * accessibility actions.
 */
class TextInput(private val service: ThumbsAccessibilityService) {

    private fun connection(): InputMethod.AccessibilityInputConnection? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.inputMethod?.let { im -> if (im.currentInputStarted) im.currentInputConnection else null }
        } else {
            null
        }

    val hasImeConnection: Boolean get() = connection() != null

    /** Returns a short description of how the text was entered. */
    fun type(text: String, clear: Boolean, method: TypeMethod): String {
        val focused = service.focusedInput()
        val ic = connection()
        val useIme = when (method) {
            TypeMethod.IME -> ic ?: throw ToolException("No input connection; tap a text field first (requires Android 13+).")
            TypeMethod.AUTO -> ic
            else -> null
        }
        if (useIme != null) {
            if (clear) {
                useIme.performContextMenuAction(android.R.id.selectAll)
                useIme.commitText("", 1, null)
            }
            useIme.commitText(text, 1, null)
            return "ime"
        }
        val node = focused?.takeIf { it.isEditable }
            ?: throw ToolException("No editable text field is focused. Tap the field first (and check the keyboard appeared), or pass its ref.")
        if (method == TypeMethod.PASTE) {
            if (clear) setText(node, "")
            paste(node, text)
            return "paste"
        }
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val (start, end) = selection(node, current.length)
        val newText = if (clear) text else current.substring(0, start) + text + current.substring(end)
        setText(node, newText)
        val cursor = if (clear) text.length else start + text.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return "set_text"
    }

    fun submit(): Boolean {
        val node = service.focusedInput()
        if (node != null && node.actionList.contains(AccessibilityAction.ACTION_IME_ENTER) &&
            node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            return true
        }
        val ic = connection() ?: return false
        sendKey(ic, KeyEvent.KEYCODE_ENTER, 0)
        return true
    }

    /** Sends a key (with optional modifiers) to the focused field. Returns false if impossible. */
    fun key(keyCode: Int, meta: Int, repeat: Int): Boolean {
        val ic = connection()
        if (ic != null) {
            repeat(repeat) { sendKey(ic, keyCode, meta) }
            return true
        }
        // Fallbacks without an IME connection
        val node = service.focusedInput()?.takeIf { it.isEditable } ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                repeat(repeat) { submit() }
                true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                var (start, end) = selection(node, current.length)
                if (start == end) {
                    if (keyCode == KeyEvent.KEYCODE_DEL) start = (start - repeat).coerceAtLeast(0) else end = (end + repeat).coerceAtMost(current.length)
                }
                setText(node, current.substring(0, start) + current.substring(end))
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start)
                    },
                )
                true
            }
            else -> false
        }
    }

    fun setClipboard(text: String, sensitive: Boolean) {
        val cm = service.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("thumbs", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
    }

    /** Reading the clipboard is only allowed for the focused app or the active IME. */
    fun getClipboard(): String? {
        val cm = service.getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(service)?.toString()
    }

    private fun paste(node: AccessibilityNodeInfo, text: String) {
        setClipboard(text, sensitive = false)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) throw ToolException("The focused field rejected paste.")
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        if (!ok) throw ToolException("The focused field rejected the text (ACTION_SET_TEXT failed). Try method='paste'.")
    }

    private fun selection(node: AccessibilityNodeInfo, length: Int): Pair<Int, Int> {
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > length || e > length) return length to length
        return minOf(s, e) to maxOf(s, e)
    }

    private fun sendKey(ic: InputMethod.AccessibilityInputConnection, keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    companion object {
        private val NAMED = mapOf(
            "enter" to KeyEvent.KEYCODE_ENTER, "return" to KeyEvent.KEYCODE_ENTER,
            "tab" to KeyEvent.KEYCODE_TAB, "space" to KeyEvent.KEYCODE_SPACE,
            "backspace" to KeyEvent.KEYCODE_DEL, "delete" to KeyEvent.KEYCODE_DEL,
            "forward_delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "escape" to KeyEvent.KEYCODE_ESCAPE, "esc" to KeyEvent.KEYCODE_ESCAPE,
            "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "move_home" to KeyEvent.KEYCODE_MOVE_HOME, "move_end" to KeyEvent.KEYCODE_MOVE_END,
            "page_up" to KeyEvent.KEYCODE_PAGE_UP, "page_down" to KeyEvent.KEYCODE_PAGE_DOWN,
            "insert" to KeyEvent.KEYCODE_INSERT, "search" to KeyEvent.KEYCODE_SEARCH,
            "menu" to KeyEvent.KEYCODE_MENU,
        )

        private val MODIFIERS = mapOf(
            "ctrl" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            "control" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            "shift" to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
            "alt" to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            "meta" to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
        )

        /** Parses "enter", "ctrl+a", "shift+tab", "f5", "KEYCODE_MOVE_END" into (keyCode, metaState). */
        fun parseKey(spec: String): Pair<Int, Int>? {
            val parts = spec.trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var meta = 0
            for (m in parts.dropLast(1)) meta = meta or (MODIFIERS[m.lowercase()] ?: return null)
            val k = parts.last()
            val lower = k.lowercase()
            val code = NAMED[lower]
                ?: if (k.length == 1 && k[0].isLetter()) KeyEvent.KEYCODE_A + (k.lowercase()[0] - 'a')
                else if (k.length == 1 && k[0].isDigit()) KeyEvent.KEYCODE_0 + (k[0] - '0')
                else if (lower.matches(Regex("f([1-9]|1[0-2])"))) KeyEvent.KEYCODE_F1 + lower.drop(1).toInt() - 1
                else KeyEvent.keyCodeFromString(if (k.startsWith("KEYCODE_", ignoreCase = true)) k.uppercase() else "KEYCODE_" + k.uppercase())
                    .takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
                ?: return null
            if (k.length == 1 && k[0].isUpperCase()) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            return code to meta
        }
    }
}
