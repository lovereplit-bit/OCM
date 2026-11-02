package app.ocmobile.ui.screens

import app.ocmobile.ui.theme.screenBackdrop
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.ocmobile.Graph
import app.ocmobile.api.str
import app.ocmobile.ui.Nav
import app.ocmobile.ui.Route
import app.ocmobile.ui.components.Ico
import app.ocmobile.ui.components.IconBtn
import app.ocmobile.ui.components.OcTextField
import app.ocmobile.ui.components.Pill
import app.ocmobile.ui.components.Spinner
import app.ocmobile.ui.components.Txt
import app.ocmobile.ui.components.tap
import app.ocmobile.ui.theme.Ic
import app.ocmobile.ui.theme.Oc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Providers with a free tier: sign in with your account on their site, then the key reaches the app by itself. */
class FreeProvider(val id: String, val title: String, val note: String, val url: String, val keyRegex: Regex?, val where: String)

val freeProviders = listOf(
    FreeProvider("github-copilot", "GitHub", "Copilot · бесплатный план", "", null, "GitHub"),
    FreeProvider("google", "Google", "Gemini · бесплатный тариф", "https://aistudio.google.com/apikey", Regex("AIza[0-9A-Za-z_\\-]{30,}"), "Google AI Studio"),
    FreeProvider("groq", "Groq", "Быстрые открытые модели · бесплатно", "https://console.groq.com/keys", Regex("gsk_[A-Za-z0-9]{30,}"), "Groq Console"),
    FreeProvider("openrouter", "OpenRouter", "Модели с меткой :free", "https://openrouter.ai/settings/keys", Regex("sk-or-[A-Za-z0-9_\\-]{20,}"), "OpenRouter"),
)

/**
 * Connecting a provider without manual copying: GitHub signs in with a device code (the server waits for confirmation itself),
 * the others sign in on the site, tap "Copy" next to the key and return to the app: the key is picked up from the clipboard.
 */
@Composable
fun ConnectScreen(providerId: String, nav: Nav) {
    val c = Oc.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fp = freeProviders.firstOrNull { it.id == providerId } ?: freeProviders.first()
    var state by remember { mutableStateOf("wait") }            // wait | saving | done | error
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }       // GitHub device code
    var manual by remember { mutableStateOf("") }
    var handled by remember { mutableStateOf(false) }
    val device = fp.keyRegex == null

    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun done(method: String) {
        Graph.prefs.signedIn = method
        Graph.prefs.loginSeen = true
        state = "done"
        scope.launch {
            runCatching { Graph.api.disposeAll() }
            runCatching { Graph.catalog.refresh() }
            Graph.catalog.selectBestFor(providerId)
            delay(1400); nav.root(Route.Home)
        }
    }

    fun acceptKey(key: String) {
        if (handled) return
        handled = true; state = "saving"
        scope.launch {
            try { Graph.api.setApiKey(providerId, key.trim()); done("key") }
            catch (e: Exception) { error = e.message ?: "Не удалось сохранить ключ"; state = "error"; handled = false }
        }
    }

    if (device) {
        LaunchedEffect(Unit) {
            try {
                val r = Graph.api.oauthAuthorize(providerId, 0, mapOf("deploymentType" to "github.com"))
                val instr = r.str("instructions").orEmpty()
                code = Regex("[A-Z0-9]{4}-[A-Z0-9]{4}").find(instr)?.value ?: instr
                val url = r.str("url") ?: "https://github.com/login/device"
                open(url)
                Graph.api.oauthCallback(providerId, 0, null)           // waiting for confirmation in the browser
                runCatching { app.ocmobile.data.FreeModels.enableCopilot(select = false) }
                done("github")
            } catch (e: Exception) { error = e.message ?: "GitHub не подтвердил вход"; state = "error" }
        }
    } else {
        LaunchedEffect(Unit) { open(fp.url) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    fp.keyRegex?.find(text)?.let { acceptKey(it.value) }
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
    }

    Box(Modifier.fillMaxSize().screenBackdrop("ConnectScreen1")) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Close, { nav.pop() })
                Txt(fp.title, Modifier.weight(1f), size = 20.sp, weight = FontWeight.Medium, align = TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (device) {
                    Txt("Введите этот код на GitHub", size = 24.sp, weight = FontWeight.Medium, align = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    if (code == null) Spinner(34.dp) else {
                        val clip = LocalClipboardManager.current
                        Box(Modifier.clip(Oc.round(24.dp)).background(c.surface).border(1.dp, c.border, Oc.round(24.dp)).tap { clip.setText(AnnotatedString(code!!)); Graph.chats.toasts.tryEmit("Код скопирован") }.padding(horizontal = 32.dp, vertical = 22.dp)) {
                            Txt(code!!, size = 36.sp, family = Oc.mono, weight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(18.dp))
                        Txt("В браузере открылась страница GitHub. Вставьте код и подтвердите вход. Экран обновится автоматически.", size = 16.sp, color = c.textDim, align = TextAlign.Center, lineHeight = 23.sp)
                        Spacer(Modifier.height(20.dp)); Spinner(28.dp)
                        Spacer(Modifier.height(20.dp))
                        Txt("Бесплатный доступ зависит от тарифа Copilot; у GitHub есть бесплатный план.", size = 14.sp, color = c.textDim, align = TextAlign.Center)
                    }
                } else {
                    Spinner(34.dp)
                    Spacer(Modifier.height(26.dp))
                    Txt("Войдите через ${fp.title} в браузере", size = 24.sp, weight = FontWeight.Medium, align = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Txt(
                        "1. Войдите на ${fp.where}.\n2. Создайте API-ключ, если его нет.\n3. Нажмите копирование ключа и вернитесь — приложение подставит его само.",
                        size = 16.sp, color = c.textDim, lineHeight = 25.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.clip(CircleShape).border(1.dp, c.border, CircleShape).tap { open(fp.url) }.padding(horizontal = 22.dp, vertical = 14.dp)) { Txt("Открыть ${fp.where} снова", size = 16.sp) }
                    Spacer(Modifier.height(28.dp))
                    Txt("или вставьте ключ вручную", size = 14.sp, color = c.textDim)
                    Spacer(Modifier.height(8.dp))
                    OcTextField(manual, { manual = it }, placeholder = "API-ключ", password = true, mono = true)
                    Spacer(Modifier.height(10.dp))
                    Pill("Подключить", { acceptKey(manual) }, enabled = manual.length > 12, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        AnimatedVisibility(state != "wait", enter = fadeIn(tween(250))) {
            Box(Modifier.fillMaxSize().screenBackdrop("ConnectScreen2"), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (state) {
                        "saving" -> { Spinner(40.dp); Spacer(Modifier.height(20.dp)); Txt("Подключение…", size = 18.sp, color = c.textDim) }
                        "done" -> {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(c.ok), contentAlignment = Alignment.Center) { Ico(Ic.Check, tint = c.bg, size = 40.dp) }
                            Spacer(Modifier.height(20.dp)); Txt("${fp.title} подключен", size = 22.sp, weight = FontWeight.Medium)
                            Txt(Graph.catalog.selectedLabel(), Modifier.padding(top = 6.dp), size = 15.sp, color = c.textDim)
                        }
                        else -> {
                            Txt(error ?: "Что-то пошло не так", size = 17.sp, color = c.danger, align = TextAlign.Center, modifier = Modifier.padding(32.dp))
                            Box(Modifier.clip(CircleShape).border(1.dp, c.border, CircleShape).tap { nav.pop() }.padding(horizontal = 22.dp, vertical = 14.dp)) { Txt("Назад", size = 16.sp) }
                        }
                    }
                }
            }
        }
    }
}
