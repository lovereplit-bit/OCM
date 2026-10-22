package dev.opencode.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** What a home-screen widget shows. [url] is optional; tapping the widget opens it, or just the app when empty. */
data class WidgetSpec(val name: String, val title: String, val text: String, val url: String)

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

            WidgetSpec(name, m["title"] as? String ?: "", m["text"] as? String ?: "", m["url"] as? String ?: "")
        }
    }

    fun get(name: String): WidgetSpec? = list().firstOrNull { it.name == name }

    fun set(spec: WidgetSpec) = save(list().filter { it.name != spec.name } + spec)

    fun remove(name: String): Boolean {
        val before = list()
        val after = before.filter { it.name != name }

        if (after.size == before.size) return false

        save(after)
        assignments().filterValues { it == name }.keys.forEach { unassign(it) }

        return true
    }

    private fun save(specs: List<WidgetSpec>) {
        val json = specs.joinToString(",", "[", "]") {
            "{\"name\":${MiniJson.quote(it.name)},\"title\":${MiniJson.quote(it.title)}," +
                "\"text\":${MiniJson.quote(it.text)},\"url\":${MiniJson.quote(it.url)}}"
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

        store.set(spec)

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

        refresh(context)

        return removed
    }

    fun describe(context: Context): String {
        val store = WidgetStore(context)
        val live = store.assignments().filterKeys { it in placed(context) }.values.toSet()

        return store.list().joinToString("\n") { "${it.name}\t${if (it.name in live) "on screen" else "not placed"}\t${it.title}" }
    }
}

/** The widget itself: one provider, many instances, each showing the spec the agent assigned to it. */
class AgentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onDeleted(context: Context, ids: IntArray) {
        val store = WidgetStore(context)

        ids.forEach { store.unassign(it) }
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

                manager.updateAppWidget(id, views(context, specs.firstOrNull { it.name == name }, id))
            }
        }

        private fun views(context: Context, spec: WidgetSpec?, id: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_agent)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

            if (spec != null && spec.url.isNotEmpty()) open.putExtra(MainActivity.EXTRA_PREVIEW, spec.url)

            v.setTextViewText(R.id.widget_title, spec?.title?.ifEmpty { null } ?: tr("OpenCode", "OpenCode"))
            v.setTextViewText(
                R.id.widget_text,
                spec?.text?.ifEmpty { null } ?: tr("Попросите агента создать виджет", "Ask the agent to create a widget"),
            )
            v.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )

            return v
        }
    }
}
