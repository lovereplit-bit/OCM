package dev.opencode.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Hands links to the system browser. Sign-in pages must open there: Google refuses OAuth inside embedded web views. */
object Browser {
    /**
     * Opens [url] in a Chrome Custom Tab when the browser supports it, so the person stays visually inside the app and
     * the Back button returns to it. With [private] the tab asks for an ephemeral session, which makes Google ask for
     * the account from scratch; browsers that do not know the option ignore it.
     */
    fun open(context: Context, url: String, private: Boolean = false) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)

        // The keys below are the public Custom Tabs protocol; no library is needed to send them.
        val extras = Bundle()

        extras.putBinder("android.support.customtabs.extra.SESSION", null)
        intent.putExtras(extras)
        intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        intent.putExtra("android.support.customtabs.extra.SHARE_STATE", 2)

        if (private) intent.putExtra("androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING", true)

        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Ui.toast(context, tr("Не найдено приложение для открытия ссылки", "No app can open this link"))
        } catch (e: Exception) {
            Log.w("browser", "could not open $url: ${e.message}")
        }
    }

    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
