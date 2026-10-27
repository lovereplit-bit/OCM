package dev.opencode.mobile

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("opencode", Context.MODE_PRIVATE)

    /** Local server or a server running elsewhere. */
    var remote: Boolean
        get() = sp.getBoolean("remote", false)
        set(value) = sp.edit().putBoolean("remote", value).apply()

    var remoteUrl: String
        get() = sp.getString("remote_url", "") ?: ""
        set(value) = sp.edit().putString("remote_url", value).apply()

    var remotePassword: String
        get() = sp.getString("remote_password", "") ?: ""
        set(value) = sp.edit().putString("remote_password", value).apply()

    /** Slower, more cautious process tracing for devices where the default makes the server crash. */
    var compat: Boolean
        get() = sp.getBoolean("compat", false)
        set(value) = sp.edit().putBoolean("compat", value).apply()

    var bindStorage: Boolean
        get() = sp.getBoolean("bind_storage", true)
        set(value) = sp.edit().putBoolean("bind_storage", value).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(value) = sp.edit().putBoolean("keep_awake", value).apply()

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", true)
        set(value) = sp.edit().putBoolean("notify", value).apply()

    var otaAuto: Boolean
        get() = sp.getBoolean("ota_auto", true)
        set(value) = sp.edit().putBoolean("ota_auto", value).apply()

    var otaWifiOnly: Boolean
        get() = sp.getBoolean("ota_wifi_only", true)
        set(value) = sp.edit().putBoolean("ota_wifi_only", value).apply()

    /** Empty means the repository this app was built from. */
    var otaRepoOverride: String
        get() = sp.getString("ota_repo", "") ?: ""
        set(value) = sp.edit().putString("ota_repo", value.trim()).apply()

    val otaRepo: String get() = otaRepoOverride.ifEmpty { BuildConfig.OTA_REPO }

    var floatingMenu: Boolean
        get() = sp.getBoolean("floating_menu", true)
        set(value) = sp.edit().putBoolean("floating_menu", value).apply()

    /**
     * Zen bypass: serve the bundled server's model requests from a local gateway (opencode2api) instead of
     * opencode.ai directly. Free models go through the anonymous "public" credential, optionally inside a
     * userspace Cloudflare WARP tunnel whose exit rotates, so a burnt-through IP stops mattering.
     */
    var zenBypass: Boolean
        get() = sp.getBoolean("zen_bypass", false)
        set(value) = sp.edit().putBoolean("zen_bypass", value).apply()

    /** Whether the gateway's free lane leaves through its own WARP tunnel (needs outbound UDP to work). */
    var zenBypassWarp: Boolean
        get() = sp.getBoolean("zen_bypass_warp", true)
        set(value) = sp.edit().putBoolean("zen_bypass_warp", value).apply()

    /** The loopback port the gateway listens on. 0 until the first start picked a free one. */
    var zenBypassPort: Int
        get() = sp.getInt("zen_bypass_port", 0)
        set(value) = sp.edit().putInt("zen_bypass_port", value).apply()

    /** One-time prompts: true once [key] has been shown. */
    fun seen(key: String) = sp.getBoolean("seen_$key", false)

    /** True once the person has signed in with a Google account on the first launch. */
    var signedIn: Boolean
        get() = sp.getBoolean("signed_in", false)
        set(value) = sp.edit().putBoolean("signed_in", value).apply()

    fun markSeen(key: String) = sp.edit().putBoolean("seen_$key", true).apply()

    fun forget(key: String) = sp.edit().remove("seen_$key").apply()

    fun menuPosition(): Pair<Float, Float> = sp.getFloat("menu_x", 1f) to sp.getFloat("menu_y", 0.45f)

    fun setMenuPosition(x: Float, y: Float) = sp.edit().putFloat("menu_x", x).putFloat("menu_y", y).apply()

    /** The password the local server and its pairing use. Random, created once, never shown. */
    val password: String get() = secret("password", 24)

    /** The token commands in the Linux environment present to the app's loopback bridge. */
    val bridgeToken: String get() = secret("bridge_token", 16)

    /** The local key the bundled server presents to the Zen bypass gateway on 127.0.0.1. */
    val zenBypassKey: String get() = secret("zen_bypass_key", 16)

    private fun secret(key: String, bytes: Int): String {
        sp.getString(key, null)?.let { return it }

        val raw = ByteArray(bytes)

        SecureRandom().nextBytes(raw)

        val value = raw.joinToString("") { "%02x".format(it) }

        sp.edit().putString(key, value).apply()

        return value
    }
}
