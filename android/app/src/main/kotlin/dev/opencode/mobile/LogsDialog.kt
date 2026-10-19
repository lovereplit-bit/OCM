package dev.opencode.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.ScrollView

object LogsDialog {
    fun text(app: App): String {
        val server = Log.tailOf(app.paths.serverLog, 24 * 1024)
        val own = Log.tail(24 * 1024)

        return buildString {
            append("OpenCode ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Server build: ").append(app.ota.current).append('\n')
            append("Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append("), ")
            append(android.os.Build.SUPPORTED_ABIS.joinToString()).append("\n\n")
            append("--- app ---\n").append(own).append("\n--- server ---\n").append(server)
        }
    }

    fun show(activity: Activity) {
        val app = activity.application as App
        val palette = Palette(activity)
        val body = text(app)
        val view = Ui.text(activity, body, 11f, palette.text, mono = true).apply {
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(view) }

        AlertDialog.Builder(
            activity,
            if (Ui.isNight(activity)) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
            .setTitle(tr("Журнал", "Logs"))
            .setView(scroll)
            .setPositiveButton(tr("Поделиться", "Share")) { _, _ ->
                activity.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), null),
                )
            }
            .setNeutralButton(tr("Копировать", "Copy")) { _, _ -> Browser.copy(activity, "OpenCode logs", body) }
            .setNegativeButton(tr("Закрыть", "Close"), null)
            .show()
    }
}
