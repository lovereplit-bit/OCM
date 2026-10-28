package dev.opencode.mobile

import java.io.File

/** Everything that decides how the Linux environment is started. Pure data, so it can be tested off-device. */
data class ProotConfig(
    val prootBin: String,
    val loader: String,
    val nativeLibDir: String,
    val rootfs: String,
    val home: String,
    val fakeProc: String,
    val prootTmp: String,
    val port: Int,
    val password: String,
    val bridgeUrl: String,
    val bridgeToken: String,
    val compat: Boolean,
    val bindSharedStorage: Boolean,
    val timeZone: String,
    val locale: String = "C.UTF-8",
    /** Host directory holding an over-the-air runtime build (`opencode` inside); null runs the bundled one. */
    val otaBinDir: String? = null,
    /** The profile's own Zen API key; null keeps the upstream default (the shared anonymous token). */
    val zenApiKey: String? = null,
    /** The local Zen bypass gateway URL (http://127.0.0.1:<port>/v1); null talks to opencode.ai directly. */
    val zenBypassUrl: String? = null,
    /** The key the server presents to the bypass gateway; only read together with [zenBypassUrl]. */
    val zenBypassKey: String? = null,
    /** The Console account signed in to this profile; null means the profile rides as a guest. */
    val consoleAccount: String? = null,
)

data class Launch(val command: List<String>, val env: Map<String, String>)

object ProotCommand {
    const val GUEST_BINARY = "/usr/local/bin/opencode"
    const val GUEST_OTA_DIR = "/opt/opencode-ota"
    const val GUEST_INSTRUCTIONS = "/etc/opencode-android/AGENTS.md"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** The prefix every proot-wrapped command shares: root filesystem, binds and the fake-root identity. */
    fun base(c: ProotConfig): List<String> {
        val args = ArrayList<String>()

        args += c.prootBin
        args += "--kill-on-exit"
        args += "--link2symlink"
        args += "-0"
        args += "-r"
        args += c.rootfs
        args += "-w"
        args += "/root"

        fun bind(host: String, guest: String = host) {
            args += "-b"
            args += if (host == guest) host else "$host:$guest"
        }

        bind("/dev")
        bind("/dev/urandom", "/dev/random")
        bind("/proc")
        bind("/proc/self/fd", "/dev/fd")
        bind("/proc/self/fd/0", "/dev/stdin")
        bind("/proc/self/fd/1", "/dev/stdout")
        bind("/proc/self/fd/2", "/dev/stderr")
        bind("/sys")

        // Android hides most of /proc from apps, and some tools abort when it is missing or empty.
        for ((name, guest) in FakeProc.targets) bind(File(c.fakeProc, name).path, guest)

        bind(c.home, "/root")

        if (c.otaBinDir != null) bind(c.otaBinDir, GUEST_OTA_DIR)

        if (c.bindSharedStorage) {
            bind("/storage/emulated/0", "/sdcard")
            bind("/storage/emulated/0/Download", "/storage/emulated/0/Download")
        }

        return args
    }

    fun guestBinary(c: ProotConfig): String = if (c.otaBinDir != null) "$GUEST_OTA_DIR/opencode" else GUEST_BINARY

    fun hostEnv(c: ProotConfig): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        env["PROOT_TMP_DIR"] = c.prootTmp
        env["PROOT_LOADER"] = c.loader
        env["LD_LIBRARY_PATH"] = c.nativeLibDir
        env["TMPDIR"] = c.prootTmp

        if (c.compat) env["PROOT_NO_SECCOMP"] = "1"

        return env
    }

    fun guestEnv(c: ProotConfig, extra: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
        val env = LinkedHashMap<String, String>()

        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["SHELL"] = "/bin/bash"
        env["PATH"] = GUEST_PATH
        env["LANG"] = c.locale
        env["LC_ALL"] = c.locale
        env["TERM"] = "xterm-256color"
        env["TZ"] = c.timeZone
        env["TMPDIR"] = "/tmp"
        env["OPENCODE_PASSWORD"] = c.password
        env["OPENCODE_CLIENT"] = "android"
        env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        env["OPENCODE_ANDROID_BRIDGE"] = c.bridgeUrl
        env["OPENCODE_ANDROID_TOKEN"] = c.bridgeToken
        env["BROWSER"] = "/usr/local/bin/android-open"

        // The server config: instructions, plus the providers this profile rides. A profile without any
        // credential of its own (no personal key, no Console sign-in) is a guest. Guests start on the Zen
        // free lane: the bypass gateway's WARP rotation keeps finding egress addresses whose shared daily
        // quota is not spent yet (verified live: nine answers in a row through the gateway while the same
        // host's direct requests all got 429), so the very first message answers with a real Zen model.
        val guest = c.zenApiKey.isNullOrBlank() && c.consoleAccount.isNullOrBlank()
        val providers = ArrayList<String>()

        if (!c.zenBypassUrl.isNullOrBlank() && !c.zenBypassKey.isNullOrBlank()) {
            // The bypass gateway stands between the server and opencode.ai: the server talks to the gateway with
            // the gateway's own local key (the personal key, if any, lives in the gateway's upstream key pool),
            // and the provider override below moves the built-in Zen provider onto the loopback endpoint. On the
            // guest line it also curates the picker: only models the anonymous lane can actually answer.
            env["OPENCODE_API_KEY"] = c.zenBypassKey
            providers += ZenBypassConfig.providerOverride(c.zenBypassUrl, guest)
        } else if (!c.zenApiKey.isNullOrBlank()) {
            // A personal Zen key of this profile: the server treats it as the profile's own credential and sends
            // every model request under it instead of the world-shared anonymous token. Verified live on 2.1.4.
            env["OPENCODE_API_KEY"] = c.zenApiKey
        }

        // The keyless lane rides alongside everywhere: a working-key profile keeps its own quota as the default
        // and can switch to it when the key misbehaves; a guest starts on it. It goes straight to the upstream
        // from the phone and never through the bypass gateway.
        providers += GuestFreeLane.providerBlock()

        env["OPENCODE_CONFIG_CONTENT"] =
            GuestFreeLane.configContent(GUEST_INSTRUCTIONS, providers, guest)

        if (c.compat) {
            // Features that lean on native code or kernel interfaces Android may not allow.
            env["OPENCODE_DISABLE_FFF"] = "1"
            env["OPENCODE_FILEWATCHER_DISABLE"] = "1"
        }

        env.putAll(extra)

        return env
    }

    fun server(c: ProotConfig): Launch {
        val command = ArrayList(base(c))

        command += "/usr/bin/env"
        command += "-i"
        for ((k, v) in guestEnv(c)) command += "$k=$v"
        command += guestBinary(c)
        command += "serve"
        command += "--hostname"
        command += "127.0.0.1"
        command += "--port"
        command += c.port.toString()

        return Launch(command, hostEnv(c))
    }

    /** Runs one program in the Linux environment with the standard guest environment. */
    fun exec(c: ProotConfig, program: String, vararg args: String): Launch {
        val command = ArrayList(base(c))

        command += "/usr/bin/env"
        command += "-i"
        for ((k, v) in guestEnv(c)) command += "$k=$v"
        command += program
        command += args

        return Launch(command, hostEnv(c))
    }

    /** A login shell in the same environment, for diagnostics. */
    fun shell(c: ProotConfig, vararg args: String): Launch {
        val command = ArrayList(base(c))

        command += "/usr/bin/env"
        command += "-i"
        for ((k, v) in guestEnv(c)) command += "$k=$v"
        command += "/bin/bash"
        command += args

        return Launch(command, hostEnv(c))
    }
}

/**
 * The keyless free lane: a config-declared provider that needs no credential at all, so a profile without a
 * personal key or a Console sign-in still has a model that answers even when the Zen free bucket runs dry. It
 * rides alongside everywhere as a manual fallback in the picker - the guest's default model itself is a free
 * Zen model now (see [ZenBypassConfig.GUEST_DEFAULT_MODEL]). Verified live through the real server on the 2.x
 * line: the declared provider is picked up, the config "model" default routes new sessions to it, and the
 * answer comes back streamed.
 */
object GuestFreeLane {
    const val PROVIDER_ID = "pollinations"
    const val MODEL_ID = "openai-fast"
    const val FALLBACK_MODEL = "$PROVIDER_ID/$MODEL_ID"
    const val BASE_URL = "https://text.pollinations.ai/openai"

    /** The provider declaration as raw JSON inside the "provider" object (static values, nothing to escape). */
    fun providerBlock(): String =
        "\"pollinations\":{\"npm\":\"@opencode/ai/providers/openai-compatible\",\"name\":\"Free (no key)\"," +
            "\"options\":{\"baseURL\":\"$BASE_URL\",\"apiKey\":\"public\"}," +
            "\"models\":{\"openai-fast\":{\"name\":\"GPT-OSS 20B\"}}}"

    /** The whole OPENCODE_CONFIG_CONTENT value: instructions, the guest default model and the provider blocks. */
    fun configContent(instructionsPath: String, providerBlocks: List<String>, guest: Boolean): String {
        val parts = ArrayList<String>()

        parts += "\"instructions\":[${MiniJson.quote(instructionsPath)}]"
        if (guest) parts += "\"model\":\"${ZenBypassConfig.GUEST_DEFAULT_MODEL}\""
        if (providerBlocks.isNotEmpty()) parts += "\"provider\":{${providerBlocks.joinToString(",")}}"

        return "{" + parts.joinToString(",") + "}"
    }
}

/**
 * The provider override the Zen bypass feeds the server through OPENCODE_CONFIG_CONTENT: the built-in Zen
 * provider keeps its model list from models.dev, but its requests move onto the local gateway. Verified live
 * on the 2.x line: with this block the server sends Bearer <OPENCODE_API_KEY> to <baseURL>/chat/completions.
 *
 * On the guest line (no personal key, no Console sign-in) the override also curates the model picker: new
 * chats start on a free Zen model the upstream gate still serves, and the models it does not - removed,
 * deprecated or paid ones - are disabled, so every model the picker offers can actually answer. Probed live
 * on 2026-10-28: the gate answers 400-unavailable for 33 free models it removed, 410 for 2 deprecated ones,
 * 403 RegionError for 4 geo-limited ones (those stay: the gateway rotates WARP exits on 403 too, so an exit
 * in a served country gets through), and quota-limits the 7 it serves. Paid models have no credential on the
 * anonymous lane at all. Regenerate the lists when the upstream gate changes.
 */
object ZenBypassConfig {
    /** The model new guest chats start on: a free Zen model the anonymous gate still serves. */
    const val GUEST_DEFAULT_MODEL = "opencode/glm-5.3-flash-free"

    /** Free models the anonymous gate answered on the 2026-10-28 probe (the geo-limited four included). */
    val guestFreeModels = listOf(
        "beam-free",
        "big-pickle",
        "cat-jam-free",
        "deepseek-v4.1-flash-free",
        "flint-40b-free",
        "glm-5.3-flash-free",
        "mimo-v2.6-flash-free",
        "muse-spark-1.2-contributor-free",
        "muse-spark-1.3-contributor-free",
        "nemotron-3.5-lightning-free",
        "nemotron-3.5-super-vl-free",
    )

    /**
     * Models the guest picker hides: the gate removed them ("Model is unavailable"), upstream deprecated them,
     * or they are paid - the anonymous lane has no credential for paid models, so they could only ever error.
     */
    private val guestHidden = setOf(
        "claude-3-5-haiku",
        "claude-fable-5",
        "claude-fable-5-1",
        "claude-haiku-4-5",
        "claude-haiku-5-5",
        "claude-opus-4-1",
        "claude-opus-4-5",
        "claude-opus-4-6",
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-5",
        "claude-opus-5-5",
        "claude-sonnet-4",
        "claude-sonnet-4-5",
        "claude-sonnet-4-6",
        "claude-sonnet-5",
        "claude-sonnet-5-5",
        "deepseek-v4-flash",
        "deepseek-v4-flash-free",
        "deepseek-v4-flash-vision-exp",
        "deepseek-v4-pro",
        "deepseek-v4.1-flash",
        "exo-free",
        "fledge-alpha-free",
        "gemini-3-flash",
        "gemini-3-pro",
        "gemini-3.1-pro",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash",
        "gemini-3.7-flash",
        "gemini-3.8-flash",
        "gemini-4-argon",
        "glm-4.6",
        "glm-4.7",
        "glm-4.7-free",
        "glm-5",
        "glm-5-free",
        "glm-5.1",
        "glm-5.2",
        "glm-5.3",
        "glm-5.3-flash",
        "gpt-5",
        "gpt-5-codex",
        "gpt-5-nano",
        "gpt-5.1",
        "gpt-5.1-codex",
        "gpt-5.1-codex-max",
        "gpt-5.1-codex-mini",
        "gpt-5.2",
        "gpt-5.2-codex",
        "gpt-5.3-codex",
        "gpt-5.3-codex-spark",
        "gpt-5.4",
        "gpt-5.4-mini",
        "gpt-5.4-nano",
        "gpt-5.4-pro",
        "gpt-5.5",
        "gpt-5.5-pro",
        "gpt-5.6-luna",
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-6-astra",
        "gpt-6-luna",
        "gpt-6-sol",
        "gpt-6.1-sol",
        "grok-4.5",
        "grok-4.6",
        "grok-4.7",
        "grok-build-0.1",
        "grok-code",
        "hy3-free",
        "hy3-preview-free",
        "kimi-k2",
        "kimi-k2-thinking",
        "kimi-k2.5",
        "kimi-k2.5-free",
        "kimi-k2.6",
        "kimi-k2.7-code",
        "kimi-k3",
        "laguna-s-2.1-free",
        "ling-2.6-flash-free",
        "ling-3.0-flash-fin-free",
        "ling-3.0-flash-free",
        "ling-3.0-tiny-free",
        "ling-3.1-flash-free",
        "ll-alpha-free",
        "longcat-2.0-free",
        "longcat-2.5-preview-free",
        "mimo-v2-flash-free",
        "mimo-v2-omni-free",
        "mimo-v2-pro-free",
        "mimo-v2.5-free",
        "minimax-m2.1",
        "minimax-m2.1-free",
        "minimax-m2.5",
        "minimax-m2.5-free",
        "minimax-m2.7",
        "minimax-m3",
        "minimax-m3-free",
        "minimax-m3.1-flash-free",
        "mistral-large-4",
        "muse-spark-1.2",
        "muse-spark-1.3",
        "muse-wonder-1.0",
        "nemotron-3-super-free",
        "nemotron-3-ultra-free",
        "north-mini-code-free",
        "qwen3-coder",
        "qwen3.5-plus",
        "qwen3.6-plus",
        "qwen3.6-plus-free",
        "qwen3.8-flash",
        "qwen3.8-max",
        "ring-2.6-1t-free",
        "space-bunny-free",
        "step-5-preview-free",
        "trinity-large-preview-free",
        "x-preview-f-free",
    )

    /** The "opencode" member of the config's "provider" object; [guest] adds the curated picker. */
    fun providerOverride(baseURL: String, guest: Boolean): String {
        val options = "\"options\":{\"baseURL\":${MiniJson.quote(baseURL)}}"

        if (!guest) return "\"opencode\":{$options}"

        val disabled = guestHidden.sorted().joinToString(",") { "\"$it\":{\"disabled\":true}" }

        return "\"opencode\":{$options,\"models\":{$disabled}}"
    }
}

/** Stand-ins for the /proc files Android denies to apps, bound over the real ones. */
object FakeProc {
    val targets: List<Pair<String, String>> = listOf(
        "loadavg" to "/proc/loadavg",
        "stat" to "/proc/stat",
        "uptime" to "/proc/uptime",
        "version" to "/proc/version",
        "vmstat" to "/proc/vmstat",
        "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
        "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
    )

    fun contents(cpus: Int = Runtime.getRuntime().availableProcessors()): Map<String, String> {
        val stat = StringBuilder("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")

        for (i in 0 until maxOf(1, cpus)) stat.append("cpu$i 244 0 359 11660 32 42 31 10 0 0\n")

        stat.append("intr 63361 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1700000000\nprocesses 26442\n")
        stat.append("procs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")

        return mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to stat.toString(),
            "uptime" to "5400.12 4800.00\n",
            "version" to "Linux version 6.2.1-PRoot-Android (proot@android) (gcc (GCC) 12.2.0) #1 SMP PREEMPT Fri Jan 1 00:00:00 UTC 2021\n",
            "vmstat" to "nr_free_pages 1000000\nnr_inactive_anon 100000\nnr_active_anon 100000\npgfault 1000000\npgmajfault 1000\n",
            "cap_last_cap" to "40\n",
            "max_user_watches" to "4096\n",
        )
    }
}
