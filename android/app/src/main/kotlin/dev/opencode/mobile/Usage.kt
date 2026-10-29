package dev.opencode.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * The server's own usage numbers. `/api/experimental/usage/limits` is where OpenCode Go and Console budgets live;
 * plain free-Zen sessions do not expose a remaining quota, so the app says so honestly instead of inventing one.
 */
object Usage {
    data class Meter(val percent: Double, val limited: Boolean, val resetsAt: Long)
    data class Go(val rolling: Meter, val weekly: Meter, val monthly: Meter)
    data class Budget(val limit: Double, val spent: Double, val scope: String, val per: String, val resetsAt: Long)
    data class Limits(val workspace: String?, val usageUrl: String?, val go: Go?, val budgets: List<Budget>)

    sealed class LimitsResult {
        data class Available(val limits: Limits) : LimitsResult()
        data object NoConsole : LimitsResult()
        data class Error(val message: String) : LimitsResult()
    }

    data class Tokens(
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val sessions: Int,
    ) {
        val total: Long get() = input + output + reasoning + cacheRead + cacheWrite
    }

    data class Report(val limits: LimitsResult, val tokens: Tokens?, val tokensError: String?) {
        val usageUrl: String? get() = (limits as? LimitsResult.Available)?.limits?.usageUrl
    }

    fun fetch(baseUrl: String, password: String): Report {
        val limits = try {
            val text = get(baseUrl, password, "/api/experimental/usage/limits")
            val root = MiniJson.obj(text) ?: throw IOException("Некорректный ответ сервера")

            if (root["data"] == null) LimitsResult.NoConsole else LimitsResult.Available(parseLimits(root["data"] as Map<*, *>))
        } catch (e: Exception) {
            LimitsResult.Error(e.message ?: e.javaClass.simpleName)
        }

        var tokens: Tokens? = null
        var tokensError: String? = null

        try {
            val text = get(baseUrl, password, "/api/session?limit=50&order=desc")
            val root = MiniJson.obj(text) ?: throw IOException("Некорректный ответ сервера")
            val list = root["data"] as? List<*> ?: throw IOException("Сервер не вернул список сессий")

            var input = 0L
            var output = 0L
            var reasoning = 0L
            var read = 0L
            var write = 0L
            var count = 0

            for (item in list) {
                val session = item as? Map<*, *> ?: continue
                val t = session["tokens"] as? Map<*, *> ?: continue
                val cache = t["cache"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

                input += long(t["input"])
                output += long(t["output"])
                reasoning += long(t["reasoning"])
                read += long(cache["read"])
                write += long(cache["write"])
                count++
            }

            tokens = Tokens(input, output, reasoning, read, write, count)
        } catch (e: Exception) {
            tokensError = e.message ?: e.javaClass.simpleName
        }

        return Report(limits, tokens, tokensError)
    }

    private fun get(baseUrl: String, password: String, path: String): String {
        val connection = URL(Urls.withoutTrailingSlash(baseUrl) + path).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Basic " + Urls.basicCredential(password))
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode

            if (code !in 200..299) {
                val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val detail = (MiniJson.obj(body)?.get("message") as? String).orEmpty()

                throw IOException(if (detail.isNotEmpty()) detail else "HTTP $code")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLimits(raw: Map<*, *>): Limits {
        val workspace = raw["workspace"] as? Map<*, *>
        val goMap = raw["go"] as? Map<*, *>
        val budgets = (raw["budgets"] as? List<*>).orEmpty().mapNotNull { parseBudget(it as? Map<*, *>) }
        val go = if (goMap == null) null else Go(
            meter(goMap["rolling"]) ?: return Limits(null, null, null, budgets),
            meter(goMap["weekly"]) ?: return Limits(null, null, null, budgets),
            meter(goMap["monthly"]) ?: return Limits(null, null, null, budgets),
        )

        return Limits(
            workspace?.get("name") as? String,
            workspace?.get("usageURL") as? String,
            go,
            budgets,
        )
    }

    private fun parseBudget(raw: Map<*, *>?): Budget? {
        raw ?: return null
        val limit = number(raw["limit"]) ?: return null
        val spent = number(raw["spent"]) ?: return null

        return Budget(
            limit,
            spent,
            raw["scope"] as? String ?: "each",
            raw["per"] as? String ?: "set",
            (raw["resetsAt"] as? Number)?.toLong() ?: 0L,
        )
    }

    private fun meter(raw: Any?): Meter? {
        val m = raw as? Map<*, *> ?: return null
        val percent = number(m["percent"]) ?: return null

        return Meter(percent, m["limited"] as? Boolean ?: false, (m["resetsAt"] as? Number)?.toLong() ?: 0L)
    }

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()

    private fun long(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
}

/** The native "Tokens and quota" sheet: the same grey scale as the rest of the app. */
object UsageDialog {
    fun show(activity: MainActivity) {
        Ui.toast(activity, tr("Запрашиваю квоту у сервера…", "Asking the server for usage…"))

        val base = ServerState.snapshot.baseUrl.ifEmpty { (activity.application as App).prefs.remoteUrl }
        val password = activity.currentPassword()

        Thread({
            val report = Usage.fetch(base, password)

            activity.runOnUiThread { render(activity, report) }
        }, "usage").apply { isDaemon = true; start() }
    }

    private fun render(activity: MainActivity, report: Usage.Report) {
        val palette = Palette(activity)
        val column = Ui.vertical(activity).apply {
            setPadding(Ui.dp(activity, 20), Ui.dp(activity, 4), Ui.dp(activity, 20), Ui.dp(activity, 4))
        }

        val limits = report.limits

        when (limits) {
            is Usage.LimitsResult.Available -> {
                val info = limits.limits

                if (info.workspace != null) {
                    column.addView(Ui.text(activity, info.workspace, 13f, palette.muted), Ui.params(bottom = Ui.dp(activity, 8)))
                }

                val go = info.go

                if (go != null) {
                    addMeter(activity, column, palette, tr("За 5 часов", "Last 5 hours"), go.rolling)
                    addMeter(activity, column, palette, tr("За неделю", "This week"), go.weekly)
                    addMeter(activity, column, palette, tr("За месяц", "This month"), go.monthly)
                }

                for (budget in info.budgets) {
                    val left = (budget.limit - budget.spent).coerceAtLeast(0.0)
                    val row = Ui.card(activity, palette)
                    row.addView(Ui.text(activity, tr("Бюджет Console", "Console budget"), 14f, palette.text, bold = true))
                    row.addView(
                        Ui.text(
                            activity,
                            tr(
                                "Потрачено $%.2f из $%.2f · осталось $%.2f",
                                "Spent $%.2f of $%.2f · $%.2f left",
                            ).format(budget.spent, budget.limit, left),
                            13f,
                            palette.muted,
                        ),
                        Ui.params(top = Ui.dp(activity, 4)),
                    )
                    if (budget.resetsAt > 0) row.addView(Ui.text(activity, tr("Сброс: ", "Resets: ") + date(budget.resetsAt), 12f, palette.faint), Ui.params(top = Ui.dp(activity, 2)))
                    column.addView(row, Ui.params(top = Ui.dp(activity, 8)))
                }

                if (go == null && info.budgets.isEmpty()) {
                    column.addView(
                        Ui.text(
                            activity,
                            tr(
                                "OpenCode Console не подключён, поэтому остаток квоты сервер не сообщает. Это нормально для бесплатных моделей Zen: лимит виден только когда он исчерпан.",
                                "OpenCode Console is not connected, so the server does not report a remaining quota. That is normal for free Zen models: the limit is visible only when it runs out.",
                            ),
                            13f,
                            palette.muted,
                        ),
                    )
                }
            }
            Usage.LimitsResult.NoConsole -> column.addView(
                Ui.text(
                    activity,
                    tr(
                        "Сервер не подключён к OpenCode Console. Остаток квоты Zen он не отдаёт — при исчерпании придёт уведомление со звуком. Ниже — сколько токенов уже израсходовано.",
                        "The server is not connected to OpenCode Console, so it does not report a remaining Zen quota — when it runs out you get a notification with a sound. Below is how many tokens were used.",
                    ),
                    13f,
                    palette.muted,
                ),
            )
            is Usage.LimitsResult.Error -> column.addView(
                Ui.text(activity, tr("Квота недоступна: ", "Usage unavailable: ") + limits.message, 13f, palette.muted),
            )
        }

        column.addView(Ui.spacer(activity, 14))

        val tokens = report.tokens

        if (tokens != null) {
            val row = Ui.card(activity, palette)
            row.addView(Ui.text(activity, tr("Израсходовано токенов", "Tokens used"), 14f, palette.text, bold = true))
            row.addView(Ui.text(activity, format(tokens.total), 24f, palette.text, bold = true), Ui.params(top = Ui.dp(activity, 2)))
            row.addView(
                Ui.text(
                    activity,
                    tr(
                        "Вход %s · выход %s · reasoning %s\nКэш: чтение %s · запись %s · сессий %d",
                        "Input %s · output %s · reasoning %s\nCache: read %s · write %s · sessions %d",
                    ).format(format(tokens.input), format(tokens.output), format(tokens.reasoning), format(tokens.cacheRead), format(tokens.cacheWrite), tokens.sessions),
                    12f,
                    palette.muted,
                ),
                Ui.params(top = Ui.dp(activity, 6)),
            )
            column.addView(row)
        } else {
            column.addView(Ui.text(activity, tr("Токены недоступны: ", "Tokens unavailable: ") + (report.tokensError ?: "?"), 13f, palette.muted))
        }

        val builder = android.app.AlertDialog.Builder(
            activity,
            if (Ui.isNight(activity)) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )

        builder.setTitle(tr("Токены и квота", "Tokens and quota"))
            .setView(android.widget.ScrollView(activity).apply { addView(column) })
            .setPositiveButton(tr("Закрыть", "Close"), null)

        val usageUrl = report.usageUrl

        if (usageUrl != null) {
            builder.setNeutralButton(tr("Открыть кабинет", "Open dashboard")) { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(usageUrl))) }
            }
        }

        builder.show()
    }

    private fun addMeter(activity: Activity, column: LinearLayout, palette: Palette, label: String, meter: Usage.Meter) {
        val left = (100.0 - meter.percent).coerceIn(0.0, 100.0)
        val row = Ui.card(activity, palette)

        row.addView(Ui.text(activity, label, 13f, palette.muted))
        row.addView(Ui.text(activity, tr("Осталось %.0f%%", "%.0f%% left").format(left), 22f, if (meter.limited) palette.danger else palette.text, bold = true))
        row.addView(
            Ui.text(
                activity,
                if (meter.limited) tr("Лимит исчерпан до сброса", "Limit reached until reset")
                else tr("Использовано %.0f%%", "%.0f%% used").format(meter.percent),
                12f,
                if (meter.limited) palette.danger else palette.muted,
            ),
        )
        if (meter.resetsAt > 0) row.addView(Ui.text(activity, tr("Сброс: ", "Resets: ") + date(meter.resetsAt), 12f, palette.faint), Ui.params(top = Ui.dp(activity, 2)))

        column.addView(row, Ui.params(top = Ui.dp(activity, 8)))
    }

    private fun date(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    private fun format(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}
