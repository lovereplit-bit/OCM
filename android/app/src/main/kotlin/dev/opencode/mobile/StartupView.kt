package dev.opencode.mobile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen status shown while the server installs, starts or has failed, and while the page loads. */
class StartupView(context: Context, private val palette: Palette, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun retry()

        fun enableCompat()

        fun showLogs()

        fun openSettings()

        fun start()
    }

    private val title = Ui.text(context, "OpenCode", 22f, palette.text, bold = true)
    private val message = Ui.text(context, "", 14f, palette.muted)
    private val error = Ui.text(context, "", 12f, palette.danger, mono = true)
    private val bar = ProgressStrip(context, palette)
    private val buttons = Ui.vertical(context)

    init {
        setBackgroundColor(palette.bg)
        isClickable = true

        val column = Ui.vertical(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 28), Ui.dp(context, 28), Ui.dp(context, 28), Ui.dp(context, 28))
        }

        val mark = Ui.icon(context, R.drawable.ic_stat_opencode, palette.text, 56)

        column.addView(mark, Ui.params(Ui.dp(context, 56), Ui.dp(context, 56), bottom = Ui.dp(context, 18)))
        column.addView(title, Ui.params(Ui.WRAP, Ui.WRAP, bottom = Ui.dp(context, 8)))
        message.gravity = Gravity.CENTER
        column.addView(message, Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(context, 18)))
        column.addView(bar, Ui.params(Ui.dp(context, 220), Ui.dp(context, 3), bottom = Ui.dp(context, 18)))

        val scroll = ScrollView(context).apply { addView(error) }

        column.addView(scroll, Ui.params(Ui.MATCH, Ui.WRAP, weight = 0f, bottom = Ui.dp(context, 18)))
        column.addView(buttons, Ui.params(Ui.dp(context, 260), Ui.WRAP))

        addView(column, LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
    }

    private fun setButtons(vararg items: Triple<String, Boolean, () -> Unit>) {
        buttons.removeAllViews()

        for ((label, primary, onClick) in items) {
            buttons.addView(Ui.button(context, label, palette, primary, onClick = onClick), Ui.params(Ui.MATCH, Ui.WRAP, bottom = Ui.dp(context, 8)))
        }
    }

    private fun common(text: String, progress: Int, err: String?) {
        message.text = text
        bar.visibility = if (progress == NONE) View.GONE else View.VISIBLE
        bar.set(progress)
        error.text = err.orEmpty()
        error.visibility = if (err.isNullOrEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
    }

    fun progress(text: String, percent: Int) {
        common(text, percent, null)
        setButtons(Triple(tr("Настройки", "Settings"), false, actions::openSettings))
    }

    fun loading() {
        common(tr("Загрузка интерфейса...", "Loading the interface..."), INDETERMINATE, null)
        setButtons()
    }

    fun failed(text: String, suggestCompat: Boolean) {
        common(tr("Не удалось запустить OpenCode", "OpenCode could not start"), NONE, text)

        val items = ArrayList<Triple<String, Boolean, () -> Unit>>()

        items += Triple(tr("Повторить", "Try again"), true, actions::retry)

        if (suggestCompat) items += Triple(tr("Включить режим совместимости", "Turn on compatibility mode"), false, actions::enableCompat)

        items += Triple(tr("Журнал", "Logs"), false, actions::showLogs)
        items += Triple(tr("Настройки", "Settings"), false, actions::openSettings)
        setButtons(*items.toTypedArray())
    }

    fun stopped() {
        common(tr("Сервер остановлен", "The server is stopped"), NONE, null)
        setButtons(Triple(tr("Запустить", "Start"), true, actions::start), Triple(tr("Настройки", "Settings"), false, actions::openSettings))
    }

    fun hide() {
        visibility = View.GONE
    }

    companion object {
        const val NONE = -2
        const val INDETERMINATE = -1
    }
}

/** A thin progress line: determinate from 0 to 100, or a sliding segment when the progress is unknown. */
class ProgressStrip(context: Context, private val palette: Palette) : View(context) {
    private var percent = StartupView.INDETERMINATE
    private var offset = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    fun set(value: Int) {
        percent = value
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        paint.color = palette.layerStrong
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        paint.color = palette.text

        if (percent >= 0) {
            canvas.drawRoundRect(0f, 0f, width * percent / 100f, height.toFloat(), height / 2f, height / 2f, paint)
        } else {
            val segment = width * 0.3f

            offset = (offset + width * 0.02f) % (width + segment)
            canvas.drawRoundRect(offset - segment, 0f, offset, height.toFloat(), height / 2f, height / 2f, paint)
            postInvalidateOnAnimation()
        }
    }
}
