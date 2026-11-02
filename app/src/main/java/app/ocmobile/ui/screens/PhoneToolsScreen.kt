package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ocmobile.Graph
import app.ocmobile.api.obj
import app.ocmobile.api.str
import app.ocmobile.data.PhoneBridge
import app.ocmobile.ui.Nav
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.OcSwitch
import app.ocmobile.ui.components.SectionLabel
import app.ocmobile.ui.components.SettingsGroup
import app.ocmobile.ui.components.SettingsRow
import app.ocmobile.ui.components.TopBar
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc
import kotlinx.coroutines.launch

/** What the agent can do on the phone: the "phone" MCP server the app runs itself. */
@Composable
fun PhoneToolsScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val prefs = Graph.prefs
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    LaunchedEffect(prefs.phoneTools, rev) {
        status = runCatching { Graph.api.mcpStatus().obj(PhoneBridge.NAME)?.str("status") }.getOrNull()
    }
    val located = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rev++ }
    val off = prefs.phoneToolsOff.split(',').filter { it.isNotBlank() }.toSet()

    Column(Modifier.fillMaxSize().screenBackdrop("PhoneToolsScreen1")) {
        TopBar("Инструменты телефона", left = { IconBtn(Ic.Back, { nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            Txt(
                "OpenCode работает на этом телефоне и может взаимодействовать с ним: создавать напоминания, ставить будильник, читать и заполнять буфер обмена, делать фото, определять геопозицию, сохранять файлы в Загрузки и открывать предпросмотр веб-страниц.",
                Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp), size = 15.sp, color = c.textDim, lineHeight = 21.sp,
            )
            SettingsGroup {
                SettingsRow(
                    "Разрешить OpenCode использовать телефон", icon = Ic.Phone,
                    subtitle = when {
                        Graph.devices.isRemote -> "Только для локального сервера на телефоне"
                        !prefs.phoneTools -> "Отключено"
                        status == "connected" -> "Подключено · инструментов: ${PhoneBridge.enabledTools().size}"
                        status == null -> "Запускается вместе с сервером"
                        else -> "Статус: $status"
                    },
                    trailing = { OcSwitch(prefs.phoneTools) { prefs.phoneTools = it; scope.launch { PhoneBridge.register(); rev++ } } },
                )
                if (!located) SettingsRow("Разрешить доступ к геопозиции", subtitle = "Необходимо для инструмента определения координат", icon = Ic.Pin, chevron = true, onClick = {
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                })
            }
            if (prefs.phoneTools) {
                SectionLabel("Инструменты")
                SettingsGroup {
                    PhoneBridge.all.forEach { t ->
                        SettingsRow(t.title, subtitle = t.desc.substringBefore(". ").removeSuffix("."), trailing = {
                            OcSwitch(t.name !in off) { on -> prefs.phoneToolsOff = (if (on) off - t.name else off + t.name).joinToString(",") }
                        })
                    }
                }
                Txt(
                    "Примеры запросов: «напомни через 20 минут размяться», «сделай фото доски и составь конспект», «сохрани отчёт в Загрузки».\n\nКамера, будильник, буфер обмена и ссылки работают, когда приложение открыто на экране (ограничение фонового режима Android).",
                    Modifier.padding(20.dp), size = 13.sp, color = c.textDim, lineHeight = 19.sp,
                )
            }
        }
    }
}
