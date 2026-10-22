package dev.opencode.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shows what killed the app. It runs in its own process (see the manifest), so it still opens when the main process
 * dies while starting. It uses no app resources, no palette and no application state on purpose: nothing here may
 * depend on the thing that just failed.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent?.getStringExtra(EXTRA_REPORT).orEmpty()
            .ifEmpty { Log.pendingCrashReport(this).orEmpty() }
            .ifEmpty { "No details were recorded. The failure may have been a native crash, which does not produce a JVM stack trace." }
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this)

        title.text = tr("OpenCode аварийно завершился", "OpenCode crashed")
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        title.setTypeface(Typeface.DEFAULT_BOLD)

        val hint = TextView(this)

        hint.text = tr(
            "Нажмите «Копировать» и отправьте этот текст разработчику.",
            "Tap Copy and send this text to the developer.",
        )
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)

        val body = TextView(this)

        body.text = report
        body.typeface = Typeface.MONOSPACE
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        body.setTextIsSelectable(true)

        val scroll = ScrollView(this)

        scroll.addView(body)

        val copy = Button(this)

        copy.text = tr("Копировать", "Copy")
        copy.setOnClickListener {
            runCatching {
                this@CrashActivity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenCode crash", report))
            }
        }

        val close = Button(this)

        close.text = tr("Закрыть", "Close")
        close.setOnClickListener { finish() }

        val buttons = LinearLayout(this)

        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val column = LinearLayout(this)

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(pad, pad * 2, pad, pad)
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(column)
        // Clear only after the report is visible. If this Activity cannot start, the report remains for the next launch.
        Log.clearPendingCrashReport(this)
    }

    companion object {
        const val EXTRA_REPORT = "report"

        /** Opens the crash screen from a dying process. Never throws: the caller is already handling a crash. */
        fun show(context: Context, report: String) {
            try {
                val intent = Intent(context, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_REPORT, report.take(30_000))

                context.startActivity(intent)
            } catch (_: Throwable) {
                // Android may refuse to open a screen from the background; the log still has the report.
            }
        }
    }
}
