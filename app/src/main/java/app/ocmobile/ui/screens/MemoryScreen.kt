package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ocmobile.Graph
import app.ocmobile.data.Memory
import app.ocmobile.ui.Nav
import app.ocmobile.ui.components.Ico
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.OcDialog
import app.ocmobile.ui.components.OcSheet
import app.ocmobile.ui.components.OcSwitch
import app.ocmobile.ui.components.OcTextField
import app.ocmobile.ui.components.Pill
import app.ocmobile.ui.components.SectionLabel
import app.ocmobile.ui.components.SettingsGroup
import app.ocmobile.ui.components.SettingsRow
import app.ocmobile.ui.components.SmallButton
import app.ocmobile.ui.components.TopBar
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.components.UsageBar
import app.ocmobile.ui.components.tap
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc
import java.io.File

private fun pretty(f: File) = f.nameWithoutExtension.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun shelfIcon(name: String): ImageVector = when (name.removeSuffix(".md")) {
    "about-me" -> Ic.User; "work" -> Ic.Laptop; "projects" -> Ic.Folder; "people" -> Ic.Chat
    "routines" -> Ic.Clock; "health" -> Ic.Drop; "ideas" -> Ic.Sparkle; "music" -> Ic.Music; "inbox" -> Ic.Mail
    else -> Ic.File
}

private fun kb(b: Long) = if (b < 1024) "$b B" else "%.1f KB".format(b / 1024f)

/**
 * Memory: a summary, what and how to remember, shelves with separate facts, your own rules.
 * Everything changed here goes straight into the agent's instructions (Memory.sync).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(nav: Nav) {
    val c = Oc.c
    val prefs = Graph.prefs
    val ctx = LocalContext.current
    var rev by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(prefs.memoryEnabled) }
    var auto by remember { mutableStateOf(prefs.memoryAuto) }
    var topics by remember { mutableStateOf(prefs.memoryTopics.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()) }
    var note by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(prefs.memoryCustom) }
    var customSaved by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf<File?>(null) }
    var showPrompt by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val shelves = remember(rev) { Memory.shelves() }
    val facts = remember(rev) { shelves.associateWith { Memory.facts(it) } }
    val total = facts.values.sumOf { it.size }
    val bytes = remember(rev) { shelves.sumOf { it.length() } + File(Memory.dir(), "MEMORY.md").length() }
    fun changed() { Memory.sync(); rev++ }

    Column(Modifier.fillMaxSize().screenBackdrop("MemoryScreen1")) {
        TopBar("Память", left = { IconBtn(Ic.Back, { nav.pop() }) }, right = {
            IconBtn(Ic.Share, {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, Memory.exportText())
                ctx.startActivity(android.content.Intent.createChooser(i, "Поделиться памятью").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(bottom = 32.dp)) {
            // Summary
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Oc.round(24.dp)).background(c.surface).border(1.dp, c.border, Oc.round(24.dp)).padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt("$total", size = 40.sp, family = Oc.heading, weight = FontWeight.SemiBold, lineHeight = 44.sp)
                    Spacer(Modifier.width(10.dp))
                    Txt(if (total == 1) "факт" else "фактов", Modifier.padding(bottom = 6.dp), size = 17.sp, color = c.textDim)
                    Spacer(Modifier.weight(1f))
                    Txt("${shelves.size} полок", Modifier.padding(bottom = 6.dp), size = 15.sp, color = c.textDim)
                }
                Spacer(Modifier.height(12.dp))
                UsageBar((bytes / 24_000f).coerceIn(0f, 1f))
                Spacer(Modifier.height(8.dp))
                Txt(
                    if (enabled) "${kb(bytes)} · загружается в каждый чат, чтобы OpenCode отвечал без чтения файлов"
                    else "Память отключена — OpenCode не считывает и не сохраняет её",
                    size = 13.sp, color = c.textDim, lineHeight = 18.sp,
                )
            }

            SectionLabel("Поведение")
            SettingsGroup {
                SettingsRow("Использовать память", subtitle = "Запоминать контекст между чатами", icon = Ic.Scroll, trailing = {
                    OcSwitch(enabled) { enabled = it; prefs.memoryEnabled = it; Memory.sync() }
                })
                SettingsRow("Сохранять автоматически", subtitle = if (auto) "Запоминает важные факты самостоятельно" else "Только по команде «запомни…»", icon = Ic.Zap, trailing = {
                    OcSwitch(auto) { auto = it; prefs.memoryAuto = it; Memory.sync() }
                })
            }

            SectionLabel("Что запоминать")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Memory.topics.forEach { (id, title, _) ->
                    val on = id in topics
                    Row(
                        Modifier.clip(Oc.pill).background(if (on) c.accent else c.surface).border(1.dp, if (on) c.accent else c.border, Oc.pill)
                            .tap(scale = 0.96f) {
                                topics = if (on) topics - id else topics + id
                                prefs.memoryTopics = Memory.topics.map { it.first }.filter { it in topics }.joinToString(",")
                                Memory.sync()
                            }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(shelfIcon(id), tint = if (on) androidx.compose.ui.graphics.Color.White else c.textDim, size = 16.dp)
                        Spacer(Modifier.width(7.dp))
                        Txt(title, size = 14.sp, color = if (on) androidx.compose.ui.graphics.Color.White else c.text)
                    }
                }
            }
            Txt(
                if (topics.isEmpty()) "Ничего не выбрано — OpenCode сохраняет факты на любые темы." else "OpenCode ведёт только выбранные полки.",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), size = 13.sp, color = c.textDim,
            )

            SectionLabel("Полки памяти")
            if (shelves.isEmpty()) {
                Txt("Здесь пока пусто. Напишите «запомни, что…» в чате или добавьте факт ниже.", Modifier.padding(horizontal = 20.dp), size = 15.sp, color = c.textDim, lineHeight = 21.sp)
            } else Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shelves.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f ->
                            val list = facts[f].orEmpty()
                            Column(
                                Modifier.weight(1f).heightIn(min = 128.dp).clip(Oc.round(20.dp)).background(c.surface).border(1.dp, c.border, Oc.round(20.dp))
                                    .tap(scale = 0.97f) { open = f }.padding(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(32.dp).clip(Oc.round(10.dp)).background(c.progressTrack), contentAlignment = Alignment.Center) {
                                        Ico(shelfIcon(f.name), tint = c.accent, size = 17.dp)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Txt("${list.size}", size = 13.sp, color = c.textDim, family = Oc.label)
                                }
                                Spacer(Modifier.height(10.dp))
                                Txt(pretty(f), size = 16.sp, weight = FontWeight.Medium, maxLines = 1)
                                Txt(list.firstOrNull() ?: kb(f.length()), Modifier.padding(top = 3.dp), size = 13.sp, color = c.textDim, maxLines = 2, lineHeight = 17.sp)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            val notes = remember(rev) { Memory.projectNotes() }
            if (notes.isNotEmpty()) {
                SectionLabel("Заметки о проектах · ${notes.size}")
                Txt("Не загружаются в каждый чат — OpenCode открывает заметку, когда речь заходит о проекте.", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), size = 13.sp, color = c.textDim, lineHeight = 18.sp)
                SettingsGroup {
                    notes.forEach { f ->
                        val first = runCatching { f.readLines().drop(1).firstOrNull { it.isNotBlank() } }.getOrNull().orEmpty()
                        SettingsRow(pretty(f), subtitle = first.take(70), icon = Ic.Folder, onClick = { open = f })
                    }
                }
            }

            SectionLabel("Рассказать что-нибудь")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OcTextField(note, { note = it }, placeholder = "Я живу в Кракове и работаю по ночам", singleLine = false, minLines = 2)
                Pill("Добавить в память", { Memory.addNote(note); note = ""; changed() }, enabled = note.isNotBlank(), modifier = Modifier.fillMaxWidth())
                Txt("Попадает во входящие; OpenCode разложит факт по нужным полкам в следующем диалоге.", size = 13.sp, color = c.textDim)
            }

            SectionLabel("Ваши правила")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OcTextField(custom, { custom = it; customSaved = false }, placeholder = "например: не больше 30 строк на полку; не сохранять адреса", singleLine = false, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(if (customSaved) "Сохранено" else "Сохранить", { prefs.memoryCustom = custom.trim(); Memory.sync(); customSaved = true }, filled = !customSaved)
                    SmallButton("Что видит OpenCode", { showPrompt = true })
                }
            }

            if (shelves.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SettingsGroup { SettingsRow("Очистить всю память", icon = Ic.Trash, titleColor = c.danger, onClick = { confirmClear = true }) }
            }
        }
    }

    open?.let { f -> ShelfSheet(f, onClose = { open = null }, onChanged = { changed() }) }
    if (showPrompt) OcSheet({ showPrompt = false }, "Что видит OpenCode", skipHalf = false) {
        Txt(
            Memory.prompt() + "\n\n" + Memory.snapshot(),
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            size = 13.sp, color = c.textDim, lineHeight = 19.sp, family = Oc.mono,
        )
    }
    if (confirmClear) {
        OcDialog("Очистить всю память?", { confirmClear = false }, "Очистить", {
            (Memory.shelves() + Memory.projectNotes()).forEach { it.delete() }
            File(Memory.dir(), "MEMORY.md").writeText("# Memory index\n\nNothing saved yet.\n")
            confirmClear = false; changed()
        }, danger = true) { Txt("Все полки будут удалены. Это действие нельзя отменить.", size = 16.sp, color = c.textDim) }
    }
}

/** A shelf: facts one by one (delete with the cross), add a fact, edit as text. */
@Composable
private fun ShelfSheet(f: File, onClose: () -> Unit, onChanged: () -> Unit) {
    val c = Oc.c
    var rev by remember { mutableStateOf(0) }
    var raw by remember { mutableStateOf(false) }
    var text by remember(f, raw) { mutableStateOf(runCatching { f.readText() }.getOrDefault("")) }
    var add by remember { mutableStateOf("") }
    val list = remember(rev) { Memory.facts(f) }
    OcSheet(onClose, pretty(f), skipHalf = false) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (raw) {
                OcTextField(text, { text = it }, singleLine = false, minLines = 8, mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SmallButton("Отмена", { raw = false })
                    Spacer(Modifier.weight(1f))
                    SmallButton("Сохранить", { f.writeText(text.trimEnd() + "\n"); raw = false; rev++; onChanged() }, filled = true)
                }
            } else {
                if (list.isEmpty()) Txt("На этой полке пока нет фактов.", size = 15.sp, color = c.textDim)
                list.forEach { fact ->
                    Row(
                        Modifier.fillMaxWidth().clip(Oc.round(16.dp)).background(c.surface).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                        Spacer(Modifier.width(12.dp))
                        Txt(fact, Modifier.weight(1f).padding(vertical = 6.dp), size = 15.sp, lineHeight = 21.sp)
                        IconBtn(Ic.Close, { Memory.removeFact(f, fact); rev++; onChanged() }, size = 36.dp, iconSize = 16.dp, tint = c.textDim)
                    }
                }
                Spacer(Modifier.height(4.dp))
                OcTextField(add, { add = it }, placeholder = "Добавить факт в ${pretty(f).lowercase()}", singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (f.name != "MEMORY.md") SmallButton("Удалить полку", { f.delete(); onChanged(); onClose() }, danger = true)
                    SmallButton("Изменить как текст", { raw = true })
                    Spacer(Modifier.weight(1f))
                    SmallButton("Добавить", { if (add.isNotBlank()) { Memory.addFact(f, add); add = ""; rev++; onChanged() } }, filled = add.isNotBlank())
                }
            }
        }
    }
}
