package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ocmobile.BuildConfig
import app.ocmobile.Graph
import app.ocmobile.api.OcJson
import app.ocmobile.api.str
import app.ocmobile.runtime.RuntimeState
import app.ocmobile.ui.Nav
import app.ocmobile.ui.components.Chip
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.OcDialog
import app.ocmobile.ui.components.OcSheet
import app.ocmobile.ui.components.OcSwitch
import app.ocmobile.ui.components.OcTextField
import app.ocmobile.ui.components.Pill
import app.ocmobile.ui.components.SectionLabel
import app.ocmobile.ui.components.Segmented
import app.ocmobile.ui.components.SettingsGroup
import app.ocmobile.ui.components.SettingsRow
import app.ocmobile.ui.components.SmallButton
import app.ocmobile.ui.components.Spinner
import app.ocmobile.ui.components.TopBar
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

// ---------- Connectors (MCP) ----------

@Composable
fun ConnectorsScreen(nav: Nav) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var add by remember { mutableStateOf(false) }
    suspend fun load() { status = runCatching { Graph.api.mcpStatus() }.getOrDefault(JsonObject(emptyMap())) }
    LaunchedEffect(Unit) { load() }
    Box(Modifier.fillMaxSize().screenBackdrop("MoreScreens1")) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Коннекторы", left = { IconBtn(Ic.Back, { nav.pop() }) })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 120.dp)) {
                val s = status
                if (s == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner(28.dp) }
                else if (s.isEmpty()) Txt("Серверы MCP не настроены. Коннекторы дают агенту дополнительные инструменты — базы данных, браузер, документацию.", Modifier.padding(24.dp), size = 16.sp, color = c.textDim, lineHeight = 23.sp)
                else SettingsGroup {
                    s.forEach { (name, v) ->
                        val st = (v as? JsonObject)?.str("status") ?: "unknown"
                        val on = st == "connected"
                        SettingsRow(name, subtitle = (v as? JsonObject)?.str("error") ?: st.replace('_', ' '), icon = Ic.Puzzle, trailing = {
                            OcSwitch(on) { want ->
                                scope.launch { runCatching { if (want) Graph.api.mcpConnect(name) else Graph.api.mcpDisconnect(name) }; load() }
                            }
                        })
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) { Pill("Добавить коннектор", { add = true }, icon = Ic.Plus) }
    }
    if (add) AddConnectorDialog({ add = false; scope.launch { load() } })
}

@Composable
private fun AddConnectorDialog(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("remote") }
    var target by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    OcDialog("Добавить коннектор", onDone, "Добавить", {
        val cfg = if (kind == "remote") buildJsonObject { put("type", "remote"); put("url", target.trim()); put("enabled", true) }
        else buildJsonObject {
            put("type", "local"); put("enabled", true)
            putJsonArray("command") { target.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
        }
        scope.launch {
            runCatching { Graph.api.patchConfig(buildJsonObject { put("mcp", buildJsonObject { put(name.trim(), cfg) }) }); Graph.api.mcpAdd(name.trim(), cfg) }
                .onSuccess { onDone() }.onFailure { error = it.message }
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OcTextField(name, { name = it }, placeholder = "Название (например, github)")
            Segmented(listOf("remote" to "Удалённый URL", "local" to "Локальная команда"), kind, { kind = it })
            OcTextField(target, { target = it }, placeholder = if (kind == "remote") "https://…/mcp" else "npx -y @modelcontextprotocol/server-everything", mono = true)
            error?.let { Txt(it, size = 14.sp, color = Oc.c.danger) }
        }
    }
}

// ---------- Local server ----------

@Composable
fun ServerScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt by Graph.runtime.state.collectAsState()
    val log by Graph.runtime.log.collectAsState()
    val prefs = Graph.prefs
    var showLog by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf<List<String>?>(null) }
    var reset by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens2")) {
        TopBar("Локальный сервер", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Oc.round(24.dp)).background(c.surface).padding(20.dp)) {
                val (label, color) = when (val s = rt) {
                    is RuntimeState.Running -> "Запущен · OpenCode ${s.version}" to c.ok
                    is RuntimeState.Starting -> "Запуск…" to c.warn
                    is RuntimeState.Crashed -> s.message to c.danger
                    else -> "Остановлен" to c.textDim
                }
                Txt(label, size = 19.sp, color = color)
                Txt("Ubuntu 24.04 (proot) · 127.0.0.1:${prefs.serverPort}", size = 15.sp, color = c.textDim, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rt is RuntimeState.Running || rt is RuntimeState.Starting) SmallButton("Остановить", { Graph.runtime.stopServer() }, danger = true)
                    else SmallButton("Запустить", { Graph.runtime.startServer() }, filled = true)
                    SmallButton("Перезапуск", { Graph.runtime.stopServer(); scope.launch { kotlinx.coroutines.delay(1200); Graph.runtime.startServer() } })
                    SmallButton("Лог", { showLog = true })
                }
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Запуск вместе с приложением", icon = Ic.Zap, trailing = { OcSwitch(prefs.autoStartServer) { prefs.autoStartServer = it } })
                SettingsRow("Не усыплять процессор во время работы", icon = Ic.Server, trailing = { OcSwitch(prefs.keepAwake) { prefs.keepAwake = it } })
                SettingsRow("Режим совместимости", subtitle = "PROOT_NO_SECCOMP — включите при сбоях на устройстве", icon = Ic.Shield, trailing = { OcSwitch(prefs.noSeccomp) { prefs.noSeccomp = it } })
                // Android 12+ kills "extra" child processes of apps (phantom process killer), and proot has many
                SettingsRow("Сервер останавливается в фоне?", subtitle = "Параметры разработчика → отключить ограничения дочерних процессов", icon = Ic.Info, onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Graph.chats.toasts.tryEmit("Сначала включите меню разработчика в настройках телефона") }
                })
            }
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsRow("Обновить OpenCode", subtitle = updating ?: "Установлено: ${prefs.opencodeVersion.ifBlank { "?" }}", icon = Ic.Download, onClick = {
                    if (updating == null) { updating = "Проверка…"; scope.launch {
                        updating = runCatching { "Обновлено до " + Graph.runtime.updateOpencode() }.getOrElse { it.message }
                    } }
                })
                SettingsRow("Установить инструменты сборки", subtitle = "git, ripgrep, curl, python3, build-essential", icon = Ic.Terminal, onClick = {
                    val lines = mutableStateListOf<String>(); tools = lines
                    scope.launch {
                        val code = Graph.runtime.installDevTools { l -> scope.launch(Dispatchers.Main) { lines.add(l) } }
                        lines.add(if (code == 0) "✓ Готово" else "✗ Ошибка (код $code)")
                    }
                })
                SettingsRow("Не закрывать сервер в фоне", subtitle = "Исключить приложение из оптимизации батареи", icon = Ic.Zap, onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                SettingsRow("Сбросить окружение", subtitle = "Удаляет Ubuntu и все файлы проекта внутри", icon = Ic.Trash, titleColor = c.danger, onClick = { reset = true })
            }
            Txt(
                "Android 12+ может принудительно закрывать дочерние процессы. Если сервер останавливается при выключенном экране, выполните в ADB:\n" +
                    "adb shell device_config put activity_manager max_phantom_processes 2147483647\n" +
                    "adb shell settings put global settings_enable_monitor_phantom_procs false",
                size = 13.sp, color = c.textDim, modifier = Modifier.padding(20.dp), lineHeight = 19.sp, family = Oc.mono,
            )
        }
    }

    if (showLog) LogSheet(log) { showLog = false }
    tools?.let { lines -> LogSheet(lines, "Установка инструментов") { tools = null } }
    if (reset) OcDialog("Сбросить окружение?", { reset = false }, "Сбросить", {
        reset = false; Graph.runtime.resetRuntime()
    }, danger = true) { Txt("Ubuntu, OpenCode и все проекты в /root будут удалены. История сессий также будет очищена.", size = 16.sp, color = Oc.c.textDim) }
}

@Composable
private fun LogSheet(lines: List<String>, title: String = "Лог сервера", onDismiss: () -> Unit) {
    val c = Oc.c
    val clip = LocalClipboardManager.current
    OcSheet(onDismiss, title, skipHalf = false) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Копировать", { clip.setText(AnnotatedString(lines.joinToString("\n"))) })
        }
        Column(Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState(), reverseScrolling = true).padding(16.dp)) {
            Txt(lines.joinToString("\n").ifBlank { "(пусто)" }, size = 11.sp, family = Oc.mono, color = c.textDim, lineHeight = 15.sp)
        }
    }
}

// ---------- Config editor ----------

@Composable
fun ConfigEditorScreen(nav: Nav) {
    val c = Oc.c
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val pretty = remember { Json { prettyPrint = true } }
    suspend fun load() {
        text = runCatching { pretty.encodeToString(JsonObject.serializer(), Graph.api.config()) }.getOrElse { "{}" }; loaded = true
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens3")) {
        TopBar("Конфигурация", left = { IconBtn(Ic.Back, { nav.pop() }) }, right = { IconBtn(Ic.Refresh, { scope.launch { load() } }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Txt("Все параметры OpenCode в opencode.json: модели, агенты, MCP, права доступа, форматирование, инструкции. Изменения объединяются и применяются сразу.", size = 14.sp, color = c.textDim, modifier = Modifier.padding(bottom = 12.dp), lineHeight = 20.sp)
            if (!loaded) Spinner(26.dp) else OcTextField(text, { text = it; msg = null }, singleLine = false, minLines = 16, mono = true)
            Spacer(Modifier.height(14.dp))
            Pill("Сохранить", {
                val o = runCatching { OcJson.parseToJsonElement(text) as JsonObject }.getOrNull()
                if (o == null) msg = "Некорректный JSON-объект"
                else scope.launch { msg = runCatching { Graph.api.patchConfig(o); "Сохранено" }.getOrElse { it.message } }
            }, modifier = Modifier.fillMaxWidth())
            msg?.let { Txt(it, size = 15.sp, color = if (it == "Сохранено") c.ok else c.danger, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

// ---------- Profile ----------

@Composable
fun ProfileScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val prefs = Graph.prefs
    var name by remember { mutableStateOf(prefs.userName) }
    var rules by remember { mutableStateOf(app.ocmobile.data.Memory.userRules()) }
    var saved by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val side = minOf(bmp.width, bmp.height)
            val sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = android.graphics.Bitmap.createScaledBitmap(sq, 512.coerceAtMost(side), 512.coerceAtMost(side), true)
            val f = File(ctx.filesDir, "avatar-${System.currentTimeMillis()}.jpg")
            f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            prefs.avatarPath = f.path
        }
    }
    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens4")) {
        TopBar("Профиль", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(112.dp) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            Spacer(Modifier.height(14.dp))
            SmallButton("Изменить фото", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Как к вам обращаться?", size = 17.sp, modifier = Modifier.padding(start = 4.dp))
                OcTextField(name, { name = it })
                Pill("Обновить имя", { prefs.userName = name.trim() }, enabled = name.trim() != prefs.userName, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Txt("Какие личные предпочтения должен учитывать OpenCode в ответах?", size = 19.sp, modifier = Modifier.padding(start = 4.dp))
                Txt("Сохраняется в глобальный AGENTS.md — применяется ко всем проектам.", size = 14.sp, color = c.textDim, modifier = Modifier.padding(start = 4.dp))
                OcTextField(rules, { rules = it; saved = false }, singleLine = false, minLines = 6, placeholder = "Отвечай на русском языке. Предпочитай небольшие понятные дифы.")
                Pill(if (saved) "Сохранено" else "Сохранить настройки", {
                    runCatching { app.ocmobile.data.Memory.saveUserRules(rules) }.onSuccess { saved = true }
                }, enabled = !saved, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------- About ----------

@Composable
fun AboutScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    fun open(u: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxSize().screenBackdrop("MoreScreens5")) {
        TopBar("О приложении", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsGroup {
                SettingsRow("OpenCode mobile", subtitle = "v${BuildConfig.VERSION_NAME}", icon = Ic.Info)
                if (app.ocmobile.ui.theme.Oc.look.id == "cloud") SettingsRow("cl0udly", subtitle = Graph.prefs.petCount.let { "Поглажен $it раз" }, icon = Ic.Cloud, chevron = true, onClick = { nav.push(app.ocmobile.ui.Route.MascotSettings) })
                SettingsRow("Исходный код на GitHub", subtitle = "lovereplit-bit/OCM", icon = Ic.GitHub, onClick = { open("https://github.com/lovereplit-bit/OCM") })
                SettingsRow("OpenCode (в контейнере)", subtitle = Graph.prefs.opencodeVersion.ifBlank { "не установлен" }, icon = Ic.Terminal)
                SettingsRow("Документация OpenCode", icon = Ic.External, onClick = { open("https://opencode.ai/docs") })
                SettingsRow("Консоль OpenCode Go", icon = Ic.External, onClick = { open("https://opencode.ai/console") })
            }
            // Updates from GitHub releases
            val upd by app.ocmobile.data.Updater.state.collectAsState()
            SettingsGroup {
                SettingsRow(
                    "Проверить обновления", icon = Ic.Download, chevron = true,
                    subtitle = when (val u = upd) {
                        is app.ocmobile.data.Updater.State.Checking -> "Проверка GitHub…"
                        is app.ocmobile.data.Updater.State.UpToDate -> "У вас установлена последняя версия"
                        is app.ocmobile.data.Updater.State.Available -> "${u.r.name} доступна — нажмите для установки"
                        is app.ocmobile.data.Updater.State.Downloading -> "Скачивание ${(u.progress * 100).toInt()}%"
                        is app.ocmobile.data.Updater.State.Installing -> "Установка…"
                        is app.ocmobile.data.Updater.State.Failed -> u.message
                        else -> "Из релизов ${app.ocmobile.data.Updater.REPO}"
                    },
                    onClick = {
                        if (upd !is app.ocmobile.data.Updater.State.Available) app.ocmobile.data.Updater.check()
                        app.ocmobile.ui.components.UpdateUi.open.value = true
                    },
                )
                SettingsRow("Проверять автоматически", subtitle = "Ищет новую версию при запуске приложения", icon = Ic.Refresh,
                    trailing = { OcSwitch(Graph.prefs.updateAuto) { Graph.prefs.updateAuto = it } })
            }
            Txt(
                "Клиент OpenCode Mobile. Поставляет proot и libtalloc из пакетов Termux (GPL-2.0 / LGPL-3.0) и скачивает Ubuntu Base 24.04 и OpenCode (MIT) при первом запуске.",
                size = 13.sp, color = c.textDim, modifier = Modifier.padding(20.dp), lineHeight = 19.sp,
            )
        }
    }
}
