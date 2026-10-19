package dev.opencode.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle

class App : Application() {
    lateinit var paths: Paths
        private set

    lateinit var prefs: Prefs
        private set

    lateinit var profiles: Profiles
        private set

    lateinit var runtime: RuntimeInstaller
        private set

    lateinit var ota: OtaManager
        private set

    private var started = 0

    /** True while any of the app's screens is visible. Notifications are only needed when it is not. */
    val inForeground: Boolean get() = started > 0

    override fun onCreate() {
        super.onCreate()

        instance = this
        paths = Paths(this)
        paths.ensure()
        Log.init(paths.logs)
        prefs = Prefs(this)
        profiles = Profiles(paths.profilesFile)
        runtime = RuntimeInstaller(this, paths)
        ota = OtaManager(this, paths, prefs)
        Notifier.init(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        Log.i("app", "started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), server ${ota.current}")
    }

    /** Reads the signed-in Console account from the running server and records it as the active profile's name. */
    fun syncAccountAsync() {
        val snap = ServerState.snapshot

        if (snap.phase != Phase.RUNNING || snap.profileId.isEmpty() || prefs.remote) return

        Thread({
            repeat(3) {
                try {
                    val accounts = ConsoleLogin(HttpTransport(snap.baseUrl, prefs.password)).signedInAccounts()

                    profiles.setAccount(snap.profileId, accounts.firstOrNull()?.label)

                    return@Thread
                } catch (e: Exception) {
                    Log.w("account", "sync failed: ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }, "account-sync").apply { isDaemon = true; start() }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
