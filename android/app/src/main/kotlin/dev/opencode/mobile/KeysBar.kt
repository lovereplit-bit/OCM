package dev.opencode.mobile

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keys a phone keyboard lacks (Esc, Tab, Ctrl combinations, arrows). It shows while the terminal has focus and sends
 * real key events to the web view, so the terminal sees them like a hardware keyboard.
 */
class KeysBar(context: Context, private val palette: Palette, private val target: () -> WebView?) : HorizontalScrollView(context) {
    private var terminalFocused = false
    private var forced: Boolean? = null

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(palette.layer)
        visibility = View.GONE

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4))
        }

        fun key(label: String, code: Int, meta: Int = 0) {
            row.addView(chip(context, label) { send(code, meta) })
        }

        key("Esc", KeyEvent.KEYCODE_ESCAPE)
        key("Tab", KeyEvent.KEYCODE_TAB)
        key("Ctrl+C", KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key("Ctrl+D", KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key("Ctrl+Z", KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key("Ctrl+L", KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key("Ctrl+R", KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key("←", KeyEvent.KEYCODE_DPAD_LEFT)
        key("↑", KeyEvent.KEYCODE_DPAD_UP)
        key("↓", KeyEvent.KEYCODE_DPAD_DOWN)
        key("→", KeyEvent.KEYCODE_DPAD_RIGHT)
        key("Home", KeyEvent.KEYCODE_MOVE_HOME)
        key("End", KeyEvent.KEYCODE_MOVE_END)
        key("PgUp", KeyEvent.KEYCODE_PAGE_UP)
        key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN)
        key("/", KeyEvent.KEYCODE_SLASH)
        key("-", KeyEvent.KEYCODE_MINUS)
        key("|", KeyEvent.KEYCODE_BACKSLASH, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        key("~", KeyEvent.KEYCODE_GRAVE, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)

        addView(row)
    }

    private fun chip(context: Context, label: String, onClick: () -> Unit): TextView =
        Ui.text(context, label, 13f, palette.text, bold = true, mono = true).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(context, 40)
            minHeight = Ui.dp(context, 36)
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
            background = Ui.ripple(context, Ui.rounded(context, palette.bg, 8f, palette.border), palette)
            layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { setMargins(Ui.dp(context, 3), 0, Ui.dp(context, 3), 0) }
            // Taking focus would hide the soft keyboard and send the next key nowhere.
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun send(code: Int, meta: Int) {
        val view = target() ?: return
        val now = SystemClock.uptimeMillis()

        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    fun setTerminalFocus(focused: Boolean) {
        terminalFocused = focused
        refresh()
    }

    /** null follows the terminal focus; true or false overrides it from the floating menu. */
    fun force(value: Boolean?) {
        forced = value
        refresh()
    }

    fun showing(): Boolean = visibility == View.VISIBLE

    private fun refresh() {
        visibility = if (forced ?: terminalFocused) View.VISIBLE else View.GONE
    }
}
