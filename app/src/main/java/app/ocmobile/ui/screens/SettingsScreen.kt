package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.ocmobile.Graph
import app.ocmobile.runtime.RuntimeState
import app.ocmobile.ui.Nav
import app.ocmobile.ui.Route
import app.ocmobile.ui.components.ChoiceRow
import app.ocmobile.ui.components.Chip
import app.ocmobile.ui.components.Ico
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.ModelSheet
import app.ocmobile.ui.components.OcSwitch
import app.ocmobile.ui.components.OcTextField
import app.ocmobile.ui.components.OcDialog
import app.ocmobile.ui.components.Pill
import app.ocmobile.ui.components.SectionLabel
import app.ocmobile.ui.components.SettingsGroup
import app.ocmobile.ui.components.SettingsRow
import app.ocmobile.ui.components.TopBar
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.theme.AppIcons
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc

@Composable
fun ChoiceDialog(title: String, options: List<Triple<String, String, ImageVector?>>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Oc.c
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(Oc.round(32.dp)).background(c.bg).border(1.dp, c.border, Oc.round(32.dp)).heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Txt(title, Modifier.padding(start = 24.dp, top = 16.dp, bottom = 10.dp), size = 22.sp)
            options.forEach { (k, l, ic) -> ChoiceRow(l, k == selected, { onSelect(k); onDismiss() }, icon = ic) }
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav, onMenu: () -> Unit) {
    val c = Oc.c
    val prefs = Graph.prefs
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rt by Graph.runtime.state.collectAsState()
    val providers by Graph.catalog.providers.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var modelSheet by remember { mutableStateOf(false) }
    var iconSheet by remember { mutableStateOf(false) }
    val hasGo = providers.any { it.id == "opencode-go" }
    val modelLabel = Graph.catalog.selectedLabel()
    val serverText = when (rt) {
        is RuntimeState.Running -> "Запущен · OpenCode ${(rt as RuntimeState.Running).version}"
        is RuntimeState.Starting -> "Запуск…"
        is RuntimeState.Crashed -> "Сбой"
        else -> "Остановлен"
    }

    Column(Modifier.fillMaxSize().screenBackdrop("SettingsScreen1")) {
        TopBar("Настройки", left = { IconBtn(Ic.Menu, onMenu) }, right = { IconBtn(Ic.Info, { nav.push(Route.About) }) }, serif = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            // Account card (in Messenger, a profile like in Telegram)
            if (Oc.look.id == "messenger") Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(96.dp) { nav.push(Route.Profile) }
                Spacer(Modifier.height(12.dp))
                Txt(prefs.userName.ifBlank { "OpenCode" }, size = 22.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Txt(if (hasGo) "OpenCode Go" else "бесплатные модели", size = 14.sp, color = c.link, modifier = Modifier.padding(top = 2.dp))
            } else Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Oc.round(22.dp)).border(1.dp, c.border, Oc.round(22.dp))
                    .clickable { nav.push(if (prefs.signedIn.isBlank()) Route.Login else Route.Providers) }.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(prefs.userName.ifBlank { "OpenCode" }, size = 19.sp)
                    Txt(
                        when (prefs.signedIn) { "github" -> "Вход через GitHub"; "google" -> "Вход через Google"; "email" -> "Вход по email"; "key" -> "Используется API-ключ"; else -> "Вход не выполнен" },
                        size = 14.sp, color = c.textDim, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(Modifier.clip(Oc.pill).background(c.text).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Txt(if (hasGo) "Go" else "Бесплатно", size = 14.sp, color = c.bg)
                }
                Spacer(Modifier.width(10.dp)); Ico(Ic.ChevronRight, tint = c.textDim, size = 22.dp)
            }
            if (prefs.signedIn.isBlank()) {
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(Oc.round(22.dp)).border(1.dp, c.border, Oc.round(22.dp)).padding(24.dp)) {
                    Txt("Больше возможностей при входе", size = 21.sp)
                    Txt("Бесплатные модели делят общий лимит сети. Войдите через GitHub или Google, чтобы получить персональные квоты.", size = 17.sp, color = c.textDim, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    Pill("Войти", { nav.push(Route.Login) })
                }
            } else if (!hasGo) {
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(Oc.round(22.dp)).border(1.dp, c.border, Oc.round(22.dp)).padding(24.dp)) {
                    Txt("Нужно больше моделей?", size = 21.sp)
                    Txt("Подключите OpenCode Go, Zen или любого стороннего провайдера для доступа к продвинутым моделям.", size = 17.sp, color = c.textDim, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    Pill("Подключить", { nav.push(Route.Providers) })
                }
            } else Spacer(Modifier.height(16.dp))

            // Like in Claude: four short groups, the rest on nested screens.
            SettingsGroup {
                SettingsRow("Профиль", icon = Ic.User, onClick = { nav.push(Route.Profile) })
                SettingsRow("Оплата и тарифы", icon = Ic.Dollar, onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://opencode.ai/console")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) })
                SettingsRow("Использование", icon = Ic.Drop, onClick = { nav.push(Route.Usage) })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Возможности", subtitle = listOfNotNull("Память".takeIf { prefs.memoryEnabled }, modelLabel.takeIf { it.isNotBlank() }).joinToString(" · "), icon = Ic.Sliders, onClick = { nav.push(Route.Capabilities) })
                SettingsRow("Сервер OpenCode", subtitle = serverText, icon = Ic.Terminal, onClick = { nav.push(Route.OcSettings) })
                SettingsRow("Коннекторы", subtitle = "Серверы MCP", icon = Ic.Puzzle, onClick = { nav.push(Route.Connectors) })
                SettingsRow("GitHub", subtitle = if (app.ocmobile.data.GitHub.signedIn) "@" + prefs.githubLogin else "Работа с репозиториями", icon = Ic.GitHub, onClick = { nav.push(Route.GitHub) })
                SettingsRow("Инструменты телефона", subtitle = if (prefs.phoneTools) "OpenCode может управлять устройством" else "Выкл", icon = Ic.Phone, onClick = { nav.push(Route.PhoneTools) })
                SettingsRow("Разрешения", subtitle = mapOf("ask" to "Спрашивать", "edits" to "Авто-правка", "auto" to "Автоматически")[prefs.permissionMode], icon = Ic.Shield, onClick = { nav.push(Route.PermissionsCfg) })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Интерфейс", subtitle = app.ocmobile.ui.theme.Looks.of(prefs.edition).title, icon = Ic.Laptop, onClick = { nav.push(Route.Edition) })
                // the mascot tab: only in the cl0udly look
                if (Oc.look.id == "cloud") SettingsRow("cl0udly", subtitle = "Маскот, наряды и мини-игры", icon = Ic.Cloud, onClick = { nav.push(Route.MascotSettings) })
                SettingsRow("Цветовая тема", subtitle = when (prefs.colorMode) { "system" -> "Системная"; "light" -> "Светлая"; "dark" -> "Тёмная"; else -> prefs.colorMode }, icon = Ic.Moon, onClick = { dialog = "color" })
                SettingsRow("Шрифт и размер", subtitle = if (prefs.fontStyle == "jetbrains") "JetBrains Mono" else if (prefs.fontStyle == "default") "По умолчанию" else "Системный", icon = Ic.FontSize, onClick = { nav.push(Route.Appearance) })
                SettingsRow("Голосовой ввод", subtitle = app.ocmobile.data.Speech.label(), icon = Ic.Waveform, onClick = { dialog = "voice" })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Тактильный отклик", icon = Ic.Vibrate, trailing = { OcSwitch(prefs.haptics) { prefs.haptics = it } })
                SettingsRow("Прогресс на экране блокировки", subtitle = "Действия агента с кнопками управления", icon = Ic.ListCheck, trailing = { OcSwitch(prefs.liveNotify) { prefs.liveNotify = it } })
                SettingsRow("Стоимость перед отправкой", subtitle = "Подсказка под полем ввода", icon = Ic.Dollar, trailing = { OcSwitch(prefs.costHint) { prefs.costHint = it } })
                SettingsRow("Уведомления", icon = Ic.Bell, onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                SettingsRow("Иконка приложения", subtitle = AppIcons.all.firstOrNull { it.id == prefs.appIcon }?.label, icon = Ic.Image, onClick = { iconSheet = true })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Сообщить об ошибке", subtitle = "Открыть Issue на GitHub с заполненными деталями", icon = Ic.Warning, onClick = { dialog = "bug" })
            }
            if (prefs.signedIn.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                SettingsGroup {
                    SettingsRow("Выйти из аккаунта", icon = Ic.External, titleColor = c.danger, onClick = {
                        scope.launch {
                            for (pid in listOf("opencode", "opencode-go", "github-copilot", "google", "groq", "openrouter")) runCatching { Graph.api.removeAuth(pid) }
                            runCatching { Graph.api.disposeAll() }
                            app.ocmobile.data.Vault.forgetAccount()
                            prefs.signedIn = ""; runCatching { Graph.catalog.refresh() }
                        }
                    })
                }
            }
        }
    }

    if (dialog == "bug") BugDialog { dialog = null }
    when (dialog) {
        "voice" -> ChoiceDialog("Язык распознавания", app.ocmobile.data.Speech.languages.map { Triple(it.first, it.second, null) }, prefs.speechLang, { prefs.speechLang = it }) { dialog = null }
        "color" -> ChoiceDialog("Цветовая тема", listOf(Triple("system", "Системная", Ic.Phone), Triple("light", "Светлая", Ic.Sun), Triple("dark", "Тёмная", Ic.Moon)), prefs.colorMode, { prefs.colorMode = it }) { dialog = null }
        "font" -> ChoiceDialog("Стиль шрифта", listOf(Triple("jetbrains", "JetBrains Mono (по умолчанию)", null), Triple("default", "С засечками", null), Triple("system", "Системный", Ic.Phone)), prefs.fontStyle, { prefs.fontStyle = it }) { dialog = null }
        "size" -> ChoiceDialog("Размер текста", listOf(Triple("small", "Мелкий", null), Triple("medium", "Средний", null), Triple("large", "Крупный", null)), prefs.textSize, { prefs.textSize = it }) { dialog = null }
        "scale" -> ChoiceDialog("Масштаб интерфейса", listOf(Triple("90", "Компактный", null), Triple("100", "Стандартный", null), Triple("110", "Крупный", null), Triple("120", "Очень крупный", null)), prefs.uiScale, { prefs.uiScale = it }) { dialog = null }
        "code" -> ChoiceDialog("Шрифт кода", listOf(Triple("mono", "Моноширинный", null), Triple("system", "Системный", null)), prefs.codeFont, { prefs.codeFont = it }) { dialog = null }
    }
    if (modelSheet) ModelSheet { modelSheet = false }
    if (iconSheet) IconPickerSheet { iconSheet = false }
}

/** "Capabilities": model, memory, agents, skills, what Claude keeps in the section of the same name. */
@Composable
fun CapabilitiesScreen(nav: Nav) {
    val c = Oc.c
    val prefs = Graph.prefs
    var modelSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().screenBackdrop("SettingsScreen2")) {
        TopBar("Возможности", left = { IconBtn(Ic.Back, { nav.pop() }) }, serif = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsGroup {
                SettingsRow("Модель", subtitle = Graph.catalog.selectedLabel(), icon = Ic.Cpu, onClick = { modelSheet = true })
                SettingsRow("Модели и малая модель", icon = Ic.Sparkle, onClick = { nav.push(Route.Models) })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Память", subtitle = if (prefs.memoryEnabled) "${app.ocmobile.data.Memory.shelves().size} полок" else "Выкл", icon = Ic.Scroll, onClick = { nav.push(Route.Memory) })
                SettingsRow("Агенты", subtitle = prefs.agent.replaceFirstChar { it.uppercase() }, icon = Ic.Bot, onClick = { nav.push(Route.Agents) })
                SettingsRow("Команда", subtitle = when (prefs.teamMode) { "manual" -> "Ваша команда · ${app.ocmobile.data.Team.members.size} помощников"; "auto" -> "Автоматически · до ${app.ocmobile.data.Team.max} помощников"; else -> "Выкл" }, icon = Ic.Users, onClick = { nav.push(Route.Team) })
                SettingsRow("Навыки", subtitle = "${app.ocmobile.data.Skills.local().size} навыков · введите / в чате", icon = Ic.Scroll, onClick = { nav.push(Route.Skills) })
            }
        }
    }
    if (modelSheet) ModelSheet { modelSheet = false }
}

/** "OpenCode": the server on the phone, providers, config, like the Claude Code section in Claude. */
@Composable
fun OcSettingsScreen(nav: Nav) {
    val c = Oc.c
    val rt by Graph.runtime.state.collectAsState()
    Column(Modifier.fillMaxSize().screenBackdrop("SettingsScreen3")) {
        TopBar("Сервер OpenCode", left = { IconBtn(Ic.Back, { nav.pop() }) }, serif = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsGroup {
                SettingsRow("Провайдеры", subtitle = "Авторизация и API-ключи", icon = Ic.Key, onClick = { nav.push(Route.Providers) })
                SettingsRow("Локальный сервер", subtitle = when (rt) {
                    is RuntimeState.Running -> "Запущен · OpenCode ${(rt as RuntimeState.Running).version}"
                    is RuntimeState.Starting -> "Запуск…"; is RuntimeState.Crashed -> "Перезапуск…"; else -> "Остановлен"
                }, icon = Ic.Server, onClick = { nav.push(Route.Server) })
                SettingsRow("Конфигурация (opencode.json)", icon = Ic.Code, onClick = { nav.push(Route.ConfigEditor) })
                SettingsRow("Резервная копия и перенос", subtitle = "Все данные в одном файле", icon = Ic.Archive, onClick = { nav.push(Route.Backup) })
            }
        }
    }
}

/** "Font style": font, text and interface size, code, one screen instead of five rows. */
@Composable
fun AppearanceScreen(nav: Nav) {
    val c = Oc.c
    val prefs = Graph.prefs
    var dialog by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().screenBackdrop("SettingsScreen4")) {
        TopBar("Шрифт и оформление", left = { IconBtn(Ic.Back, { nav.pop() }) }, serif = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsGroup {
                SettingsRow("Шрифт", subtitle = if (prefs.fontStyle == "jetbrains") "JetBrains Mono" else if (prefs.fontStyle == "default") "По умолчанию" else "Системный", icon = Ic.FontSize, onClick = { dialog = "font" })
                SettingsRow("Размер текста", subtitle = when (prefs.textSize) { "small" -> "Мелкий"; "large" -> "Крупный"; else -> "Средний" }, icon = Ic.File, onClick = { dialog = "size" })
                SettingsRow("Масштаб интерфейса", subtitle = when (prefs.uiScale) { "90" -> "Компактный"; "110" -> "Крупный"; "120" -> "Очень крупный"; else -> "Стандартный" }, icon = Ic.Expand, onClick = { dialog = "scale" })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Шрифт кода", subtitle = if (prefs.codeFont == "mono") "Моноширинный" else "Системный", icon = Ic.Terminal, onClick = { dialog = "code" })
                SettingsRow("Перенос длинных строк", icon = Ic.Diff, trailing = { OcSwitch(prefs.wrapLines) { prefs.wrapLines = it } })
            }
        }
    }
    when (dialog) {
        "font" -> ChoiceDialog("Шрифт", listOf(Triple("jetbrains", "JetBrains Mono (по умолчанию)", null), Triple("default", "С засечками", null), Triple("system", "Системный", Ic.Phone)), prefs.fontStyle, { prefs.fontStyle = it }) { dialog = null }
        "size" -> ChoiceDialog("Размер текста", listOf(Triple("small", "Мелкий", null), Triple("medium", "Средний", null), Triple("large", "Крупный", null)), prefs.textSize, { prefs.textSize = it }) { dialog = null }
        "scale" -> ChoiceDialog("Масштаб интерфейса", listOf(Triple("90", "Компактный", null), Triple("100", "Стандартный", null), Triple("110", "Крупный", null), Triple("120", "Очень крупный", null)), prefs.uiScale, { prefs.uiScale = it }) { dialog = null }
        "code" -> ChoiceDialog("Шрифт кода", listOf(Triple("mono", "Моноширинный", null), Triple("system", "Системный", null)), prefs.codeFont, { prefs.codeFont = it }) { dialog = null }
    }
}

/** What happened: a couple of lines and, optionally, the tail of the server log; then GitHub opens. */
@Composable
private fun BugDialog(onDismiss: () -> Unit) {
    val c = Oc.c
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var what by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(true) }
    OcDialog("Сообщить об ошибке", onDismiss, confirmText = "Открыть GitHub", onConfirm = {
        app.ocmobile.data.BugReport.open(ctx, "Bug: " + what.trim().lineSequence().firstOrNull().orEmpty().take(70), what, withLog)
        onDismiss()
    }) {
        Column {
            Txt("Что вы делали и что пошло не так?", size = 15.sp, color = c.textDim)
            Spacer(Modifier.height(10.dp))
            OcTextField(what, { what = it }, Modifier.fillMaxWidth(), placeholder = "Чат завис после…", singleLine = false, minLines = 3)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Прикрепить лог сервера", size = 15.sp)
                    Txt("Последние строки (пароли и ключи исключены)", size = 13.sp, color = c.textDim)
                }
                OcSwitch(withLog) { withLog = it }
            }
            Spacer(Modifier.height(8.dp))
            Txt("Вы сможете просмотреть всё на GitHub перед отправкой.", size = 13.sp, color = c.textDim, lineHeight = 18.sp)
        }
    }
}
