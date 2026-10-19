package dev.opencode.mobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase { IDLE, INSTALLING, STARTING, RUNNING, FAILED, STOPPED }

/** What the screens need to know about the server, published by the service. */
data class Snapshot(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    /** 0..100 while installing or downloading, -1 when not measurable. */
    val progress: Int = -1,
    val profileId: String = "",
    val port: Int = 0,
    /** The base address the web UI is served from. */
    val baseUrl: String = "",
    /** Bumps on every successful (re)start so the UI knows to reload. */
    val generation: Int = 0,
    val error: String? = null,
    /** True when the failure looks like it needs compatibility mode. */
    val suggestCompat: Boolean = false,
)

object ServerState {
    @Volatile
    var snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(change: (Snapshot) -> Snapshot) {
        val next = synchronized(this) {
            change(snapshot).also { snapshot = it }
        }

        main.post { listeners.forEach { it(next) } }
    }

    /** Calls [listener] on the main thread now and on every change. Returns a function that unsubscribes. */
    fun listen(listener: (Snapshot) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot)

        return { listeners -= listener }
    }
}
