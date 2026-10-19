package dev.opencode.mobile

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, ERROR }

data class UpdateSnapshot(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val message: String = "",
    val progress: Int = -1,
    /** What the last check found, if anything. */
    val available: CheckResult.Available? = null,
    /** A downloaded server build that applies at the next start. */
    val stagedVersion: String? = null,
    /** True once the last check completed, so the screen can say "up to date". */
    val checked: Boolean = false,
    /** A verified APK waiting for the person to confirm the install. */
    val appApk: File? = null,
)

/** One place that runs update work and publishes its progress, for the settings screen and the background service. */
object Updates {
    @Volatile
    var snapshot = UpdateSnapshot()
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(UpdateSnapshot) -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    fun listen(listener: (UpdateSnapshot) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot)

        return { listeners -= listener }
    }

    private fun set(change: (UpdateSnapshot) -> UpdateSnapshot) {
        val next = synchronized(this) { change(snapshot).also { snapshot = it } }

        main.post { listeners.forEach { it(next) } }
    }

    private fun spawn(name: String, work: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return

        Thread({
            try {
                work()
            } catch (e: Throwable) {
                Log.e("updates", "$name failed", e)
                set { it.copy(phase = UpdatePhase.ERROR, message = e.message ?: e.javaClass.simpleName, progress = -1) }
            } finally {
                busy.set(false)
            }
        }, "updates-$name").apply { isDaemon = true; start() }
    }

    fun check(app: App) = spawn("check") { runCheck(app) }

    private fun runCheck(app: App): CheckResult {
        set { it.copy(phase = UpdatePhase.CHECKING, message = "", progress = -1) }

        val result = app.ota.check()

        set {
            when (result) {
                is CheckResult.Available -> it.copy(phase = UpdatePhase.IDLE, available = result, checked = true, message = "")
                CheckResult.UpToDate -> it.copy(phase = UpdatePhase.IDLE, available = null, checked = true, message = "")
                CheckResult.NotConfigured -> it.copy(
                    phase = UpdatePhase.ERROR,
                    available = null,
                    checked = false,
                    message = tr("Репозиторий обновлений не задан", "No update repository is set"),
                )
                is CheckResult.Failed -> it.copy(phase = UpdatePhase.ERROR, available = null, checked = false, message = result.message)
            }
        }

        return result
    }

    /** Downloads and stages the server build from the last check. [force] ignores the Wi-Fi-only setting. */
    fun fetchRuntime(app: App, force: Boolean) = spawn("runtime") { stage(app, force) }

    private fun stage(app: App, force: Boolean) {
        val update = snapshot.available?.runtime ?: return

        if (!app.runtime.ready()) {
            set { it.copy(phase = UpdatePhase.ERROR, message = tr("Сначала должно запуститься Linux-окружение", "The Linux runtime has to start first")) }

            return
        }

        set { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, message = update.manifest.upstreamVersion) }

        val tester = ServerProcess(app.paths)
        val result = app.ota.download(
            update,
            force,
            selfTest = { dir, manifest ->
                val config = tester.config(app.prefs, app.profiles.active, "", dir)
                val run = tester.run(config, 45, ProotCommand.guestBinary(config), "--version")

                run != null && run.first == 0 && run.second.contains(manifest.upstreamVersion)
            },
            progress = { p -> set { it.copy(progress = p) } },
        )

        set {
            when (result) {
                is DownloadResult.Staged -> it.copy(
                    phase = UpdatePhase.READY,
                    stagedVersion = result.version,
                    // A pending app update stays on offer; only the server build has been taken care of.
                    available = it.available?.copy(runtime = null)?.takeIf { rest -> rest.app != null },
                    progress = 100,
                    message = result.version,
                )
                DownloadResult.NeedsUnmeteredNetwork -> it.copy(
                    phase = UpdatePhase.ERROR,
                    progress = -1,
                    message = tr("Обновление ждёт Wi-Fi. Нажмите «Скачать сейчас», чтобы использовать мобильную сеть.", "The update waits for Wi-Fi. Tap Download now to use mobile data."),
                )
                is DownloadResult.Failed -> it.copy(phase = UpdatePhase.ERROR, progress = -1, message = result.message)
            }
        }

        if (result is DownloadResult.Staged && !app.inForeground) {
            Notifier.update(
                app,
                tr("OpenCode ${result.version} загружен. Он включится при следующем запуске сервера.", "OpenCode ${result.version} is downloaded. It turns on the next time the server starts."),
            )
        }
    }

    /** Downloads, verifies and installs the newer app. Android shows its own confirmation. */
    fun installApp(app: App) = spawn("app") {
        val update = snapshot.available?.app ?: return@spawn

        if (!Permissions.canInstallPackages(app)) {
            set { it.copy(phase = UpdatePhase.ERROR, message = tr("Разрешите установку из этого приложения в настройках Android и повторите", "Allow installs from this app in Android settings and try again")) }
            Permissions.requestInstallPackages(app)

            return@spawn
        }

        set { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, message = update.manifest.app?.versionCode?.toString().orEmpty()) }

        val apk = AppUpdater(app, app.paths, app.ota).download(update) { p -> set { it.copy(progress = p) } }

        set { it.copy(phase = UpdatePhase.READY, progress = 100, appApk = apk, message = tr("Подтвердите установку", "Confirm the install")) }
        AppUpdater(app, app.paths, app.ota).install(apk)
    }

    /** The background routine: look for an update and fetch it when the settings allow. Never installs anything by itself. */
    fun auto(app: App) = spawn("auto") {
        if (!app.prefs.otaAuto || !app.ota.online()) return@spawn
        if (app.prefs.otaWifiOnly && !app.ota.unmeteredNow()) return@spawn

        val result = runCheck(app)

        if (result is CheckResult.Available) {
            if (result.runtime != null) stage(app, false)

            if (result.app != null && !app.inForeground) {
                Notifier.update(app, tr("Доступно обновление приложения. Откройте настройки, чтобы установить.", "An app update is available. Open settings to install it."))
            }
        }
    }

    fun dismissError() = set { if (it.phase == UpdatePhase.ERROR) it.copy(phase = UpdatePhase.IDLE, message = "") else it }
}
