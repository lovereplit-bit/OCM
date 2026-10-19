package dev.opencode.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Signs in to the OpenCode Console (the account behind Zen and Go). The sign-in page opens in the system browser, where
 * the person picks Google and then the Google account, or adds another one. This screen follows the attempt and
 * returns when it completes.
 */
class LoginActivity : Activity() {
    private val app: App get() = application as App
    private lateinit var palette: Palette
    private lateinit var status: TextView
    private lateinit var codeBox: LinearLayout
    private lateinit var code: TextView
    private lateinit var buttons: LinearLayout

    private var unsubscribe: (() -> Unit)? = null
    private var attempt: LoginAttempt? = null

    @Volatile
    private var alive = true

    @Volatile
    private var running = false

    private var firstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        palette = Palette(this)
        firstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)

        val column = Ui.vertical(this).apply {
            setPadding(Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 32), Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 24))
        }

        column.addView(Ui.icon(this, R.drawable.ic_user, palette.text, 40), Ui.params(Ui.dp(this, 40), Ui.dp(this, 40), bottom = Ui.dp(this, 16)))
        column.addView(Ui.text(this, tr("Вход в аккаунт OpenCode", "Sign in to OpenCode"), 22f, palette.text, bold = true), Ui.params(bottom = Ui.dp(this, 8)))
        column.addView(
            Ui.text(
                this,
                tr(
                    "Аккаунт нужен для Zen и покупки доступа к моделям. Откроется страница входа: выберите Google, затем нужный аккаунт Google или добавьте другой.",
                    "The account unlocks Zen and paid model access. A sign-in page opens: choose Google, then pick a Google account or add another one.",
                ),
                14f,
                palette.muted,
            ),
            Ui.params(bottom = Ui.dp(this, 20)),
        )

        codeBox = Ui.card(this, palette).apply { visibility = View.GONE }
        codeBox.addView(Ui.text(this, tr("Код подтверждения", "Confirmation code"), 12f, palette.faint))
        code = Ui.text(this, "", 28f, palette.text, bold = true, mono = true).apply { setPadding(0, Ui.dp(this@LoginActivity, 6), 0, Ui.dp(this@LoginActivity, 6)) }
        codeBox.addView(code)
        codeBox.addView(Ui.text(this, tr("Если страница спросит код, он совпадает с этим.", "If the page asks for a code, it is this one."), 12f, palette.muted))
        column.addView(codeBox, Ui.params(bottom = Ui.dp(this, 16)))

        status = Ui.text(this, tr("Ожидание сервера...", "Waiting for the server..."), 14f, palette.text)
        column.addView(status, Ui.params(bottom = Ui.dp(this, 16)))

        buttons = Ui.vertical(this)
        column.addView(buttons, Ui.params())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            addView(column)
        })

        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        showButtons(waiting = true)
        ServerService.send(this)
        unsubscribe = ServerState.listen { onState(it) }
    }

    override fun onDestroy() {
        alive = false
        unsubscribe?.invoke()
        super.onDestroy()
    }

    private fun onState(s: Snapshot) {
        when (s.phase) {
            Phase.RUNNING -> if (!running && attempt == null) begin(s)
            Phase.FAILED -> {
                status.text = tr("Сервер не запустился: ", "The server did not start: ") + s.error.orEmpty()
                showButtons(waiting = false, retry = true)
            }
            else -> status.text = if (s.message.isNotEmpty()) s.message else tr("Ожидание сервера...", "Waiting for the server...")
        }
    }

    private fun begin(s: Snapshot) {
        running = true
        status.text = tr("Запрашиваю страницу входа...", "Requesting the sign-in page...")
        showButtons(waiting = true)

        val login = ConsoleLogin(HttpTransport(s.baseUrl, app.prefs.password))

        Thread({
            try {
                val started = login.start()

                runOnUiThread {
                    attempt = started
                    code.text = started.code.orEmpty()
                    codeBox.visibility = if (started.code != null) View.VISIBLE else View.GONE
                    status.text = tr("Ждём, пока вы войдёте в браузере...", "Waiting for you to sign in in the browser...")
                    showButtons(waiting = false)
                    Browser.open(this, started.url)
                }

                poll(login, started)
            } catch (e: Exception) {
                Log.w("login", "start failed: ${e.message}")
                runOnUiThread {
                    status.text = tr("Не удалось начать вход: ", "Could not start the sign-in: ") + (e.message ?: "")
                    showButtons(waiting = false, retry = true)
                }
            } finally {
                running = false
            }
        }, "login").apply { isDaemon = true; start() }
    }

    private fun poll(login: ConsoleLogin, started: LoginAttempt) {
        var errors = 0

        while (alive) {
            try {
                Thread.sleep(2000)

                when (val result = login.status(started)) {
                    LoginStatus.Complete -> {
                        runOnUiThread { done() }

                        return
                    }
                    LoginStatus.Expired -> {
                        runOnUiThread {
                            attempt = null
                            codeBox.visibility = View.GONE
                            status.text = tr("Время входа истекло. Начните заново.", "The sign-in timed out. Start again.")
                            showButtons(waiting = false, retry = true)
                        }

                        return
                    }
                    is LoginStatus.Failed -> {
                        runOnUiThread {
                            attempt = null
                            codeBox.visibility = View.GONE
                            status.text = if (result.message.contains("access_denied")) {
                                tr("Вход отклонён на странице.", "The sign-in was declined on the page.")
                            } else {
                                tr("Вход не удался: ", "Sign-in failed: ") + result.message
                            }
                            showButtons(waiting = false, retry = true)
                        }

                        return
                    }
                    LoginStatus.Pending -> errors = 0
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // A few failed polls in a row are normal while the phone switches apps; keep trying.
                if (++errors > 15) {
                    runOnUiThread {
                        attempt = null
                        status.text = tr("Потеряна связь с сервером: ", "Lost the connection to the server: ") + (e.message ?: "")
                        showButtons(waiting = false, retry = true)
                    }

                    return
                }
            }
        }
    }

    private fun done() {
        UiBus.reloadRequested = true
        app.syncAccountAsync()
        Ui.toast(this, tr("Вход выполнен", "Signed in"))
        finish()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showButtons(waiting: Boolean, retry: Boolean = false) {
        buttons.removeAllViews()

        val current = attempt

        if (retry) {
            buttons.addView(
                Ui.button(this, tr("Повторить", "Try again"), palette, primary = true) {
                    attempt = null

                    val s = ServerState.snapshot

                    if (s.phase == Phase.RUNNING) begin(s) else ServerService.send(this, ServerService.ACTION_RESTART)
                },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        if (current != null) {
            buttons.addView(
                Ui.button(this, tr("Открыть страницу входа", "Open the sign-in page"), palette, primary = true) { Browser.open(this, current.url) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
            buttons.addView(
                Ui.button(this, tr("Другой аккаунт (приватное окно)", "Another account (private window)"), palette) { Browser.open(this, current.url, private = true) },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
            buttons.addView(
                Ui.button(this, tr("Скопировать ссылку", "Copy the link"), palette) {
                    Browser.copy(this, "OpenCode sign-in", current.url)
                    Ui.toast(this, tr("Ссылка скопирована", "Link copied"))
                },
                Ui.params(bottom = Ui.dp(this, 8)),
            )
        }

        buttons.addView(Ui.button(this, if (firstRun) tr("Пропустить", "Skip") else tr("Отмена", "Cancel"), palette) { finish() }, Ui.params())

        buttons.gravity = Gravity.CENTER_HORIZONTAL
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }
}
