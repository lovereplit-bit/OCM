package dev.opencode.mobile

import android.app.Activity

/**
 * Connecting and disconnecting the profile's own OpenCode API key from the menu and settings. The key lands on the
 * profile, the server restarts and from then on every request of the profile goes under this personal credential.
 * No key is required: without one the profile uses the free lane under this install's own identity.
 */
object ApiKey {
    /** Asks for the profile's own API key and applies it. */
    fun ask(activity: Activity, prefill: String = "") {
        val app = activity.application as App

        Ui.input(
            activity,
            tr("Персональный API-ключ профиля", "The profile's personal API key"),
            tr("Ключ из консоли OpenCode", "A key from the OpenCode console"),
            prefill,
            password = true,
        ) { raw ->
            val key = raw.trim()

            if (key.isNotEmpty()) {
                val id = app.profiles.active.id

                // Every request of this profile now goes under this key: the server gets it as OPENCODE_API_KEY on
                // the next start.
                app.profiles.setZenApiKey(id, key)
                UiBus.reloadRequested = true
                ServerService.send(activity, ServerService.ACTION_RESTART)
                Ui.toast(activity, tr("Личный ключ подключён", "Personal key connected"))
            }
        }
    }

    /** Disconnects the key: the profile goes back to the free lane. Applies on the next server start. */
    fun remove(activity: Activity) {
        val app = activity.application as App
        val id = app.profiles.active.id

        app.profiles.setZenApiKey(id, null)
        UiBus.reloadRequested = true
        ServerService.send(activity, ServerService.ACTION_RESTART)
        Ui.toast(activity, tr("Ключ убран: снова бесплатный режим", "Key removed: back to the free lane"))
    }
}
