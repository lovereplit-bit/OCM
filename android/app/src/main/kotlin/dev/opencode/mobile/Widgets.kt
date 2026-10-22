package dev.opencode.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** What a home-screen widget shows. [url] is optional; tapping the widget opens it, or just the app when empty. */
data class WidgetSpec(val name: String, val title: String, val text: String, val url: String, val src: String = "", val w: Int = 0, val h: Int = 0) {
    /**
     * Rich content the agent sent for this widget: an HTML document (which may embed CSS and JS), an SVG image or
     * a raster picture such as PNG, stored as raw opaque bytes in a one-byte-per-character string via [WidgetContent.bytesOf].
     * Empty means the plain title/text widget.
     */
    val isRich get() = src.isNotEmpty()

    val richKind get() = WidgetContent.kindOf(src)

    /** Content renders at this many pixels. Zero falls back to the default canvas size in [WidgetContent]. */
    val pixelW: Int get() = if (w > 0) w.coerceIn(1, WidgetContent.MAX_PX) else WidgetContent.DEFAULT_W

    val pixelH: Int get() = if (h > 0) h.coerceIn(1, WidgetContent.MAX_PX) else WidgetContent.DEFAULT_H

    fun hash(): String = WidgetContent.hashOf(src, w, h)
}

/**
 * Widgets the agent defines for itself. The agent sets the content with `android-widget`; the app keeps it here and
 * draws it on every placed instance of [AgentWidgetProvider]. Each placed instance is tied to one named spec.
 */
class WidgetStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun list(): List<WidgetSpec> {
        val items = MiniJson.parseOrNull(sp.getString("specs", "[]").orEmpty()) as? List<*> ?: return emptyList()

        return items.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null

            WidgetSpec(
                name,
                m["title"] as? String ?: "",
                m["text"] as? String ?: "",
                m["url"] as? String ?: "",
                m["src"] as? String ?: "",
                (m["w"] as? Long)?.toInt() ?: 0,
                (m["h"] as? Long)?.toInt() ?: 0,
            )
        }
    }

    fun get(name: String): WidgetSpec? = list().firstOrNull { it.name == name }

    fun set(spec: WidgetSpec) = save(list().filter { it.name != spec.name } + spec)

    fun remove(name: String): Boolean {
        val before = list()
        val after = before.filter { it.name != name }

        if (after.size == before.size) return false

        save(after)

        return true
    }

    private fun save(specs: List<WidgetSpec>) {
        val json = specs.joinToString(",", "[", "]") {
            val base = "{\"name\":${MiniJson.quote(it.name)},\"title\":${MiniJson.quote(it.title)}," +
                "\"text\":${MiniJson.quote(it.text)},\"url\":${MiniJson.quote(it.url)}"

            if (!it.isRich) "$base}"
            else "$base,\"src\":${MiniJson.quote(it.src)},\"w\":${it.w},\"h\":${it.h}}"
        }

        sp.edit().putString("specs", json).apply()
    }

    /** Which spec each placed widget (by Android widget id) shows. */
    fun assignments(): Map<Int, String> {
        val m = MiniJson.parseOrNull(sp.getString("ids", "{}").orEmpty()) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<Int, String>()

        for ((k, v) in m) {
            val id = (k as? String)?.toIntOrNull() ?: continue

            if (v is String) out[id] = v
        }

        return out
    }

    fun assign(id: Int, name: String) = saveIds(assignments() + (id to name))

    fun unassign(id: Int) = saveIds(assignments() - id)

    private fun saveIds(ids: Map<Int, String>) {
        sp.edit().putString("ids", ids.entries.joinToString(",", "{", "}") { "\"${it.key}\":${MiniJson.quote(it.value)}" }).apply()
    }

    /** The spec waiting for the user to confirm placing it, set when the agent asks the launcher to pin a widget. */
    var pending: String
        get() = sp.getString("pending", "") ?: ""
        set(value) = sp.edit().putString("pending", value).apply()
}

object Widgets {
    /** Requests from the agent's side that a planted rich widget play its tap animation. */
    const val ACTION_TAP = "dev.opencode.mobile.WIDGET_TAP"
    const val EXTRA_ID = "widgetId"

    /** Pushes the current content to every placed widget. Safe to call from any thread. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AgentWidgetProvider::class.java))

        if (ids.isNotEmpty()) AgentWidgetProvider.render(context, manager, ids)
    }

    private fun placed(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AgentWidgetProvider::class.java))

    /** Creates or updates a widget. Returns a short message for the agent. */
    fun set(context: Context, spec: WidgetSpec, pin: Boolean): String {
        val store = WidgetStore(context)
        val isNew = store.get(spec.name) == null

        RichWidgets.forget(spec.name)

        store.set(spec)
        store.pending = ""

        val manager = AppWidgetManager.getInstance(context)
        val shown = store.assignments().filterKeys { it in placed(context) }.containsValue(spec.name)

        if (!shown && pin && manager.isRequestPinAppWidgetSupported) {
            store.pending = spec.name

            val asked = manager.requestPinAppWidget(ComponentName(context, AgentWidgetProvider::class.java), null, null)

            refresh(context)

            return if (asked) "widget ${spec.name}: waiting for the person to confirm placing it on the home screen"
            else "widget ${spec.name}: saved, the launcher refused to place it; add the OpenCode widget by hand"
        }

        refresh(context)

        return if (shown) "widget ${spec.name}: updated"
        else if (isNew) "widget ${spec.name}: saved; add the OpenCode widget from the widget list to see it"
        else "widget ${spec.name}: saved"
    }

    fun remove(context: Context, name: String): Boolean {
        val removed = WidgetStore(context).remove(name)

        RichWidgets.forget(name)

        refresh(context)

        return removed
    }

    fun describe(context: Context): String {
        val store = WidgetStore(context)
        val live = store.assignments().filterKeys { it in placed(context) }.values.toSet()

        return store.list().joinToString("\n") { "${it.name}\t${if (it.name in live) "on screen" else "not placed"}\t${it.title}" }
    }

    /** The person tapped a rich widget on the home screen; ask its page to play the tap animation. */
    fun onTap(context: Context, id: Int, done: () -> Unit) {
        val store = WidgetStore(context)
        val name = store.assignments()[id]
        val spec = name?.let { store.get(it) }

        if (spec == null || spec.richKind != WidgetContent.KIND_HTML) {
            done()
            return
        }

        RichWidgets.tap(context, spec, done)
    }
}

/** The widget itself: one provider, many instances, each showing the spec the agent assigned to it. */
class AgentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onDeleted(context: Context, ids: IntArray) {
        val store = WidgetStore(context)

        ids.forEach { store.unassign(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_TAP) {
            val result = goAsync()

            Widgets.onTap(context, intent.getIntExtra(Widgets.EXTRA_ID, AppWidgetManager.INVALID_APPWIDGET_ID)) { result.finish() }

            return
        }

        super.onReceive(context, intent)
    }

    companion object {
        fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = WidgetStore(context)
            val specs = store.list()

            for (id in ids) {
                var name = store.assignments()[id]

                if (name == null || specs.none { it.name == name }) {
                    // A new instance takes the spec the agent just asked to pin, else one nobody shows yet.
                    name = store.pending.takeIf { p -> specs.any { it.name == p } }
                        ?: specs.firstOrNull { it.name !in store.assignments().values }?.name
                        ?: specs.firstOrNull()?.name

                    if (name != null) store.assign(id, name)

                    store.pending = ""
                }

                val spec = specs.firstOrNull { it.name == name }

                manager.updateAppWidget(id, renderViews(context, spec, id))
            }
        }

        fun renderViews(context: Context, spec: WidgetSpec?, id: Int): RemoteViews {
            if (spec != null && spec.isRich) {
                val cached = RichWidgets.cached(spec)

                if (cached != null) return RichWidgets.views(context, spec, cached)

                RichWidgets.load(context, spec)
            }

            return textView(context, spec)
        }

        internal fun textView(context: Context, spec: WidgetSpec?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_agent)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

            if (spec != null && spec.url.isNotEmpty()) open.putExtra(MainActivity.EXTRA_PREVIEW, spec.url)

            v.setViewVisibility(R.id.widget_image, View.GONE)
            v.setTextViewText(R.id.widget_title, spec?.title?.ifEmpty { null } ?: tr("OpenCode", "OpenCode"))
            v.setTextViewText(
                R.id.widget_text,
                spec?.text?.ifEmpty { null } ?: tr("Попросите агента создать виджет", "Ask the agent to create a widget"),
            )
            v.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, spec?.name?.hashCode() ?: 0x4F4321, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )

            return v
        }
    }
}
