package dev.opencode.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the local server back after the phone boots or the app is updated. Android does not run anything while the
 * phone is powered off, so this is the closest the app can get to "always on": start as soon as Android is up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? App ?: return

        if (!app.prefs.startOnBoot || app.prefs.remote) return

        Log.i("boot", "starting the local server after ${intent.action}")

        runCatching { ServerService.send(context, ServerService.ACTION_START) }
            .onFailure { Log.w("boot", "could not start the server: ${it.message}") }
    }
}
