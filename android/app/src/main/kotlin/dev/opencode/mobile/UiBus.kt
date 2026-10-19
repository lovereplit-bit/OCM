package dev.opencode.mobile

/** What the background service may ask the visible screen to do. Set by the main activity while it exists. */
interface UiHost {
    fun showPreview(url: String)

    fun openExternal(url: String)
}

object UiBus {
    @Volatile
    var host: UiHost? = null

    /** The port of the loopback bridge, so it is never offered as a page to preview. */
    @Volatile
    var bridgePort: Int = 0

    /** Set by screens that change what the web UI shows (a new sign-in); the main screen reloads on return. */
    @Volatile
    var reloadRequested: Boolean = false
}
