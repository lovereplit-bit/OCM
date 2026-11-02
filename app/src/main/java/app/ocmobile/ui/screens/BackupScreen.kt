package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ocmobile.Graph
import app.ocmobile.data.Backup
import app.ocmobile.ui.Nav
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.OcDialog
import app.ocmobile.ui.components.SectionLabel
import app.ocmobile.ui.components.SettingsGroup
import app.ocmobile.ui.components.SettingsRow
import app.ocmobile.ui.components.Spinner
import app.ocmobile.ui.components.TopBar
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc
import kotlinx.coroutines.launch

/** Backup and moving to another build or phone. */
@Composable
fun BackupScreen(nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Backup.state.collectAsState()
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pending = it }
    val busy = st is Backup.State.Working

    Column(Modifier.fillMaxSize().screenBackdrop("BackupScreen1")) {
        TopBar("Резервное копирование", left = { IconBtn(Ic.Back, { if (!busy) nav.pop() }) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            Txt(
                "Один файл со всем содержимым: чаты, авторизации, память, навыки, проекты, GitHub и настройки. Восстановите его на другом телефоне или после переустановки.",
                Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp), size = 15.sp, color = c.textDim, lineHeight = 21.sp,
            )
            SettingsGroup {
                SettingsRow("Создать полную копию", subtitle = "Сохраняется в Загрузки/OpenCode", icon = Ic.Download,
                    onClick = { if (!busy && !Graph.devices.isRemote) scope.launch { Backup.export(ctx) } })
                SettingsRow("Восстановить из копии", subtitle = "Заменит текущие данные", icon = Ic.Undo,
                    onClick = { if (!busy) picker.launch(arrayOf("*/*")) })
            }
            if (Graph.devices.isRemote) Txt("Резервное копирование работает для локального сервера на телефоне. Сначала переключитесь на него.", Modifier.padding(20.dp), size = 14.sp, color = c.textDim)
            when (val s = st) {
                is Backup.State.Working -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(18.dp); Spacer(Modifier.width(12.dp)); Txt(s.step + "…", size = 15.sp)
                }
                is Backup.State.Saved -> {
                    SectionLabel("Готово")
                    Txt("${s.where} · ${"%.1f".format(java.util.Locale.US, s.bytes / 1048576.0)} MB", Modifier.padding(horizontal = 20.dp), size = 15.sp, family = Oc.mono)
                    Spacer(Modifier.height(6.dp))
                    Txt("Храните файл в надёжном месте: он содержит токены авторизации.", Modifier.padding(horizontal = 20.dp), size = 14.sp, color = c.textDim)
                }
                is Backup.State.Restored -> Txt("Восстановлено. Перезапуск…", Modifier.padding(20.dp), size = 15.sp)
                is Backup.State.Failed -> Txt(s.message, Modifier.padding(20.dp), size = 14.sp, color = c.danger, lineHeight = 19.sp)
                else -> {}
            }
        }
    }
    pending?.let { uri ->
        OcDialog("Восстановить из этой копии?", { pending = null }, confirmText = "Восстановить", danger = true, onConfirm = {
            pending = null; scope.launch { Backup.restore(ctx, uri) }
        }) { Txt("Чаты, память, навыки и настройки из файла заменят текущие. Приложение будет перезапущено по завершении.", size = 15.sp, color = c.textDim, lineHeight = 21.sp) }
    }
}
