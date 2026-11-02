package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ocmobile.Graph
import app.ocmobile.data.UsageWindow
import app.ocmobile.ui.Nav
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.OcDialog
import app.ocmobile.ui.components.OcTextField
import app.ocmobile.ui.components.SectionLabel
import app.ocmobile.ui.components.SettingsGroup
import app.ocmobile.ui.components.SettingsRow
import app.ocmobile.ui.components.SmallButton
import app.ocmobile.ui.components.Spinner
import app.ocmobile.ui.components.TopBar
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.components.UsageBar
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc
import kotlinx.coroutines.launch

private fun resetText(w: UsageWindow): String {
    val at = w.resetAt ?: return "Нет расходов в этот период"
    val ms = at - System.currentTimeMillis()
    if (ms <= 0) return "Сброс прямо сейчас"
    val min = ms / 60_000
    return "Освободится через " + when {
        min < 60 -> "$min мин"
        min < 60 * 48 -> "${min / 60} ч ${min % 60} мин"
        else -> "${min / 1440} дн ${(min % 1440) / 60} ч"
    }
}

private fun money(x: Double) = if (x >= 100) "$%.0f".format(x) else "$%.2f".format(x)

@Composable
private fun UsageCard(title: String, w: UsageWindow) {
    val c = Oc.c
    val pct = w.percent
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Oc.round(24.dp)).background(c.surface).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, size = 21.sp, modifier = Modifier.weight(1f))
            Txt(if (pct > 0 && pct < 1) "<1% использовано" else "%.0f%% использовано".format(pct), size = 17.sp, color = c.textDim)
        }
        Spacer(Modifier.height(14.dp))
        UsageBar(w.fraction.toFloat(), warn = pct >= 85)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Txt(resetText(w), size = 16.sp, color = c.textDim, modifier = Modifier.weight(1f))
            Txt("${money(w.used)} / ${money(w.limit)}", size = 16.sp, color = c.textDim)
        }
    }
}

@Composable
fun UsageScreen(nav: Nav) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    val snap by Graph.usage.snapshot.collectAsState()
    val refreshing by Graph.usage.refreshing.collectAsState()
    var info by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Graph.usage.recompute(); runCatching { Graph.usage.backfill() } }

    Column(Modifier.fillMaxSize().screenBackdrop("UsageScreen1")) {
        TopBar("Использование", left = { IconBtn(Ic.Back, { nav.pop() }) }, right = { IconBtn(Ic.Info, { info = true }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageCard("Текущая сессия · 5 часов", snap.fiveHour)
            SectionLabel("Недельный лимит", Modifier.padding(bottom = 0.dp))
            UsageCard("Последние 7 дней", snap.week)
            SectionLabel("Месячный лимит", Modifier.padding(bottom = 0.dp))
            UsageCard("Последние 30 дней", snap.month)

            SectionLabel("Модели · за 30 дней", Modifier.padding(bottom = 0.dp))
            if (snap.byModel.isEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Oc.round(24.dp)).background(c.surface).padding(20.dp)) {
                    Txt("Платных запросов пока нет", size = 20.sp)
                    Txt("Когда агент отвечает, стоимость запросов к модели появится здесь.", size = 16.sp, color = c.textDim, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                SettingsGroup {
                    snap.byModel.take(12).forEach { m ->
                        SettingsRow(
                            title = m.model.ifBlank { "unknown" }, subtitle = "${m.provider} · ${m.messages} ответов · ${"%.1f".format(m.tokens / 1000.0)}k токенов",
                            trailing = { Txt(money(m.cost), size = 17.sp) },
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Изменить лимиты", { edit = true })
                SmallButton(if (refreshing) "Обновление…" else "Обновить", { scope.launch { Graph.usage.backfill() } })
                if (refreshing) Spinner(18.dp, stroke = 2.dp)
            }
            Txt(
                "Учтено ${snap.tracked} ответов на этом устройстве. Всего за последние 30 дней: ${money(snap.totalMonth)}.",
                size = 14.sp, color = c.textDim, modifier = Modifier.padding(horizontal = 20.dp), lineHeight = 20.sp,
            )
        }
    }

    if (info) OcDialog("Как это рассчитывается", { info = false }) {
        Txt(
            "OpenCode не предоставляет публичного API для остатка квоты, поэтому приложение суммирует стоимость каждого ответа, прошедшего через сервер на этом телефоне за плавающие окна в 5 часов, 7 дней и 30 дней, и сопоставляет с заданными лимитами (по умолчанию: \$12 / \$30 / \$60). Расход с других устройств или сайта здесь не учитывается — официальные данные доступны на opencode.ai/console.",
            size = 15.sp, color = Oc.c.textDim, lineHeight = 22.sp,
        )
    }
    if (edit) EditLimitsDialog { edit = false }
}

@Composable
private fun EditLimitsDialog(onDone: () -> Unit) {
    val p = Graph.prefs
    var a by remember { mutableStateOf(p.limit5h.toString().removeSuffix(".0")) }
    var w by remember { mutableStateOf(p.limitWeek.toString().removeSuffix(".0")) }
    var m by remember { mutableStateOf(p.limitMonth.toString().removeSuffix(".0")) }
    var prov by remember { mutableStateOf(p.limitProviders) }
    OcDialog("Лимиты (USD)", onDone, "Сохранить", {
        a.toDoubleOrNull()?.let { p.limit5h = it }; w.toDoubleOrNull()?.let { p.limitWeek = it }; m.toDoubleOrNull()?.let { p.limitMonth = it }
        p.limitProviders = prov; Graph.usage.recompute(); onDone()
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("5 часов", size = 14.sp, color = Oc.c.textDim); OcTextField(a, { a = it }, keyboardType = KeyboardType.Decimal)
            Txt("7 дней", size = 14.sp, color = Oc.c.textDim); OcTextField(w, { w = it }, keyboardType = KeyboardType.Decimal)
            Txt("30 дней", size = 14.sp, color = Oc.c.textDim); OcTextField(m, { m = it }, keyboardType = KeyboardType.Decimal)
            Txt("Учитываемые провайдеры (через запятую, пусто = все)", size = 14.sp, color = Oc.c.textDim); OcTextField(prov, { prov = it })
        }
    }
}
