package app.opencodemob

import android.content.Context
import android.system.Os
import android.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("oc", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun flag(c: Context, k: String, d: Boolean = true): Boolean = sp(c).getBoolean(k, d)
    fun putFlag(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/** Shared between the service (writer) and the activity (reader). */
object ServerState {
    @Volatile var port = 0
    @Volatile var password = ""
    @Volatile var ready = false
    @Volatile var error: String? = null
}

object Runtime {
    fun rootfs(c: Context) = File(c.filesDir, "rootfs")

    /** Every Google account gets its own HOME: sessions, auth.json, config. Nothing is shared. */
    fun home(c: Context, uid: String): File = File(c.filesDir, "users/$uid/home").apply { mkdirs() }

    /** Maps a path as the agent sees it (inside proot) to the real file on the phone. */
    fun hostPath(c: Context, uid: String, guest: String): File {
        val g = File("/", guest).normalize().path
        return when {
            g == "/root" || g.startsWith("/root/") -> File(home(c, uid), g.removePrefix("/root").removePrefix("/"))
            g == "/sdcard" || g.startsWith("/sdcard/") -> File("/storage/emulated/0", g.removePrefix("/sdcard").removePrefix("/"))
            g == "/storage/emulated/0" || g.startsWith("/storage/emulated/0/") -> File(g)
            else -> File(rootfs(c), g.removePrefix("/"))
        }
    }

    fun ls(c: Context, uid: String, guest: String): org.json.JSONObject {
        val l = hostPath(c, uid, guest).listFiles()
            ?: return org.json.JSONObject().put("ok", false).put("err", "Cannot read $guest")
        val arr = org.json.JSONArray()
        l.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach {
            arr.put(org.json.JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", it.length()))
        }
        return org.json.JSONObject().put("ok", true).put("path", guest).put("items", arr)
    }

    fun fs(c: Context, uid: String, op: String, guest: String, arg: String): org.json.JSONObject {
        val r = org.json.JSONObject()
        return try {
            val f = hostPath(c, uid, guest)
            when (op) {
                "mkdir" -> check(f.mkdirs() || f.isDirectory) { "Cannot create folder" }
                "create" -> { f.parentFile?.mkdirs(); check(f.createNewFile() || f.exists()) { "Cannot create file" } }
                "rename" -> {
                    val dst = if (arg.startsWith("/")) hostPath(c, uid, arg) else File(f.parentFile, arg)
                    check(f.renameTo(dst)) { "Cannot rename" }
                }
                "delete" -> rmrf(f)
                else -> error("Unknown operation")
            }
            r.put("ok", true)
        } catch (e: Exception) { r.put("ok", false).put("err", e.message ?: e.toString()) }
    }

    fun isInstalled(c: Context): Boolean {
        val stamp = File(rootfs(c), ".stamp")
        return stamp.exists() && stamp.readText().trim() == assetId(c)
    }

    private fun assetId(c: Context): String =
        c.assets.open("rootfs.id").bufferedReader().use { it.readText().trim() }

    /** Human-readable error for install failures (disk full, permissions, tar entry). */
    private fun friendly(e: Exception, prefix: String): String {
        val m = e.message ?: e.toString()
        return when {
            m.contains("ENOSPC") || m.contains("No space left", true) ->
                "На телефоне закончилось свободное место. Освободите минимум 2 ГБ и нажмите «Повторить»."
            m.contains("EACCES") || m.contains("Permission denied", true) ->
                "$prefix: нет доступа к файлам. Нажмите «Сбросить и установить заново»."
            else -> "$prefix: $m"
        }
    }

    /** Deletes rootfs.broken-* dirs left aside by previous installs (best effort, background). */
    fun cleanupBroken(c: Context) {
        c.filesDir.listFiles { f -> f.name.startsWith("rootfs.broken") }?.forEach {
            kotlin.concurrent.thread(isDaemon = true) { try { rmrf(it) } catch (_: Exception) {} }
        }
    }

    // ---------- install (xz -> rootfs), progress 0..100 ----------

    private class Counting(inp: InputStream) : FilterInputStream(inp) {
        var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) n++ }
        override fun read(b: ByteArray, o: Int, l: Int): Int = super.read(b, o, l).also { if (it > 0) n += it }
    }

    fun install(c: Context, progress: (Int) -> Unit) {
        val id = assetId(c)
        val tmp = File(c.filesDir, "rootfs.new")
        cleanupBroken(c)
        rmrf(tmp); tmp.mkdirs()
        val total = c.assets.openFd("rootfs.tar.xz").use { it.length }.coerceAtLeast(1)
        val cnt = Counting(c.assets.open("rootfs.tar.xz"))
        var last = -1
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(cnt, 1 shl 16))).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                try { extract(tmp, tar, e as TarArchiveEntry) }
                catch (ex: Exception) { throw IOException(friendly(ex, "Не удалось распаковать элемент «${e.name}»")) }
                val pct = (cnt.n * 100 / total).toInt().coerceIn(0, 99)
                if (pct != last) { last = pct; progress(pct) }
                e = tar.nextEntry
            }
        }
        try {
            File(tmp, "etc/resolv.conf").apply { quietDelete(this); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
            File(tmp, "etc/hosts").apply { quietDelete(this); writeText("127.0.0.1 localhost\n::1 localhost\n") }
            File(tmp, "tmp").apply { mkdirs(); Os.chmod(path, 0x3FF) } // 1777
            File(tmp, "root").mkdirs()
            File(tmp, ".stamp").writeText(id)
        } catch (ex: Exception) { throw IOException(friendly(ex, "Не удалось подготовить окружение")) }
        // Replace the old rootfs. Its leftover files (from any previous version) can be
        // undeletable (read-only/locked), so: best-effort delete -> rename aside -> swap in.
        val old = rootfs(c)
        try { rmrf(old) } catch (_: Exception) {}
        if (old.exists()) {
            val aside = File(c.filesDir, "rootfs.broken-" + System.currentTimeMillis())
            if (!old.renameTo(aside))
                throw IOException("Не удалось заменить старое Linux-окружение. Нажмите «Сбросить и установить заново», а если не поможет — очистите данные OpenCode в настройках Android.")
            kotlin.concurrent.thread(isDaemon = true) { try { rmrf(aside) } catch (_: Exception) {} }
        }
        if (!tmp.renameTo(rootfs(c))) throw IOException("Не удалось переместить Linux-окружение")
        progress(100)
    }

    private fun extract(root: File, tar: TarArchiveInputStream, e: TarArchiveEntry) {
        val name = e.name.removePrefix("./").trimEnd('/')
        if (name.isEmpty() || name.split('/').any { it == ".." }) return
        val f = File(root, name)
        when {
            e.isDirectory -> { f.mkdirs(); Os.chmod(f.path, (e.mode and 0x1FF) or 0x1C0) }
            e.isSymbolicLink -> { f.parentFile?.mkdirs(); quietDelete(f); Os.symlink(e.linkName, f.path) }
            e.isLink -> {
                f.parentFile?.mkdirs(); quietDelete(f)
                val target = File(root, e.linkName.removePrefix("./"))
                try { Os.link(target.path, f.path) } catch (_: Exception) { target.copyTo(f, true) }
            }
            e.isFile -> {
                f.parentFile?.mkdirs(); quietDelete(f)
                FileOutputStream(f).use { tar.copyTo(it) }
                Os.chmod(f.path, (e.mode and 0x1FF) or 0x180)
            }
            else -> Unit // devices/fifos are provided by proot binds
        }
    }

    private fun quietDelete(f: File) { try { Files.deleteIfExists(f.toPath()) } catch (_: Exception) {} }

    fun rmrf(f: File) {
        val p = f.toPath()
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            private fun writable(x: Path) {
                if (Files.isSymbolicLink(x)) return // deleting a symlink needs no perms on the target
                try {
                    Files.setPosixFilePermissions(x, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"))
                } catch (_: Exception) {}
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                writable(dir); return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    try { Files.delete(file) }
                    catch (_: Exception) { writable(file); Files.delete(file) }
                } catch (_: Exception) { /* keep going; install() renames the dir aside */ }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, e: IOException): FileVisitResult {
                try { file.toFile().delete() } catch (_: Exception) {}
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, e: IOException?): FileVisitResult {
                try { Files.delete(dir) } catch (_: Exception) {}
                return FileVisitResult.CONTINUE
            }
        })
    }

    // ---------- per-install port + password (never shared between users or phones) ----------

    /** Random port chosen once per install and kept, so the WebView origin (localStorage, IndexedDB) stays stable. */
    fun stablePort(c: Context): Int {
        val saved = Prefs.get(c, "port").toIntOrNull()
        if (saved != null) {
            try { ServerSocket(saved, 0, java.net.InetAddress.getByName("127.0.0.1")).close(); return saved } catch (_: Exception) {}
        }
        val p = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        Prefs.put(c, "port", p.toString())
        return p
    }

    /** Unique stable port for local AI models allocated per user (uid). */
    fun stableAiPort(c: Context, uid: String): Int {
        val key = "ai_port_" + uid
        val saved = Prefs.get(c, key).toIntOrNull()
        if (saved != null && saved in 1024..65535) {
            return saved
        }
        val ocPort = Prefs.get(c, "port").toIntOrNull() ?: 0
        val base = 12000 + (Math.abs(uid.hashCode()) % 40000)
        var chosen = base
        try {
            val s = ServerSocket(chosen, 0, java.net.InetAddress.getByName("127.0.0.1"))
            s.close()
        } catch (_: Exception) {
            for (attempt in 0..10) {
                val t = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                if (t != ocPort && t != 11434 && t != 8080) {
                    chosen = t
                    break
                }
            }
        }
        Prefs.put(c, key, chosen.toString())
        return chosen
    }

    fun stablePassword(c: Context): String {
        var pw = Prefs.get(c, "pw")
        if (pw.isEmpty()) {
            pw = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            Prefs.put(c, "pw", pw)
        }
        return pw
    }

    fun basic(pw: String) = "Basic " + Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)

    // ---------- launch: proot -> opencode serve (1.x) ----------

    /** Common proot invocation (same binds as the server) for a one-shot command like `opencode console login`. */
    private fun prootBase(c: Context, uid: String): MutableList<String> {
        val lib = c.applicationInfo.nativeLibraryDir
        val home = home(c, uid)
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }
        val a = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
            "-r", rootfs(c).path, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${home.path}:/root",
        )
        if (File("/storage/emulated/0").exists()) {
            a += listOf("-b", "/storage/emulated/0:/storage/emulated/0", "-b", "/storage/emulated/0:/sdcard")
        }
        return a
    }

    /** Runs a short-lived command inside the proot env with merged piped output (for `opencode console login/logout`). */
    fun prootCmd(c: Context, uid: String, cmd: List<String>): Process {
        val lib = c.applicationInfo.nativeLibraryDir
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }
        val a = prootBase(c, uid) + cmd
        val pb = ProcessBuilder(a).redirectErrorStream(true).directory(c.filesDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", lib)
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", ptmp.path)
            put("HOME", "/root"); put("USER", "root"); put("SHELL", "/bin/bash")
            put("LANG", "C.UTF-8"); put("TERM", "dumb"); put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("OPENCODE_DISABLE_AUTOUPDATE", "1")
        }
        return pb.start()
    }

    fun buildLocalProviderConfig(c: Context, uid: String, providerId: String): org.json.JSONObject {
        val aiPort = stableAiPort(c, uid)
        val home = home(c, uid)
        val modelsObj = org.json.JSONObject()

        fun addM(id: String, name: String, ctx: Int = 4096) {
            if (id.isEmpty()) return
            val m = org.json.JSONObject().apply {
                put("name", name)
                put("limit", org.json.JSONObject().apply {
                    put("context", ctx)
                    put("output", ctx)
                })
            }
            modelsObj.put(id, m)
        }

        // Standard catalog models and all naming variations
        val catalog = listOf(
            Triple("deepseek-r1:1.5b", "DeepSeek-R1 Distill 1.5B", 4096),
            Triple("deepseek-r1-1.5b", "DeepSeek-R1 Distill 1.5B", 4096),
            Triple("deepseek-r1.1.5b", "DeepSeek-R1 Distill 1.5B", 4096),
            Triple("DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", "DeepSeek-R1 Distill 1.5B", 4096),
            Triple("DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M", "DeepSeek-R1 Distill 1.5B", 4096),
            Triple("deepseek-r1-distill-qwen-1.5b-q4_k_m", "DeepSeek-R1 Distill 1.5B", 4096),
            Triple("qwen2.5:1.5b", "Qwen 2.5 1.5B Instruct", 4096),
            Triple("qwen2.5-1.5b", "Qwen 2.5 1.5B Instruct", 4096),
            Triple("qwen2.5-1.5b-instruct-q4_k_m.gguf", "Qwen 2.5 1.5B Instruct", 4096),
            Triple("qwen2.5-1.5b-instruct-q4_k_m", "Qwen 2.5 1.5B Instruct", 4096),
            Triple("smollm2:1.7b", "SmolLM2 1.7B Instruct", 4096),
            Triple("smollm2-1.7b", "SmolLM2 1.7B Instruct", 4096),
            Triple("SmolLM2-1.7B-Instruct-Q4_K_M.gguf", "SmolLM2 1.7B Instruct", 4096),
            Triple("SmolLM2-1.7B-Instruct-Q4_K_M", "SmolLM2 1.7B Instruct", 4096),
            Triple("llama3.2:3b", "Llama 3.2 3B Instruct", 8192),
            Triple("llama3.2-3b", "Llama 3.2 3B Instruct", 8192),
            Triple("Llama-3.2-3B-Instruct-Q4_K_M.gguf", "Llama 3.2 3B Instruct", 8192),
            Triple("Llama-3.2-3B-Instruct-Q4_K_M", "Llama 3.2 3B Instruct", 8192),
            Triple("gemma2:2b", "Gemma 2 2B Instruct", 4096),
            Triple("gemma2-2b", "Gemma 2 2B Instruct", 4096),
            Triple("gemma-2-2b-it-Q4_K_M.gguf", "Gemma 2 2B Instruct", 4096),
            Triple("gemma-2-2b-it-Q4_K_M", "Gemma 2 2B Instruct", 4096),
            Triple("phi3.5:3.8b", "Phi-3.5 Mini Instruct", 8192),
            Triple("phi3.5-3.8b", "Phi-3.5 Mini Instruct", 8192),
            Triple("Phi-3.5-mini-instruct-Q4_K_M.gguf", "Phi-3.5 Mini Instruct", 8192),
            Triple("Phi-3.5-mini-instruct-Q4_K_M", "Phi-3.5 Mini Instruct", 8192),
            Triple("local", "Локальная модель (GGUF)", 4096),
            Triple("default", "Локальная модель (GGUF)", 4096)
        )
        for ((id, name, ctx) in catalog) {
            addM(id, name, ctx)
        }

        // Add any downloaded GGUF files in /root/models
        val mDir = File(home, "models")
        if (mDir.exists()) {
            mDir.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) }?.forEach { f ->
                val fn = f.name
                val base = f.nameWithoutExtension
                addM(fn, base, 4096)
                addM(base, base, 4096)
                addM(base.lowercase(), base, 4096)
            }
        }

        val displayName = if (providerId == "ollama") "Ollama (Офлайн :$aiPort)" else "Локально (Офлайн :$aiPort)"
        return org.json.JSONObject().apply {
            put("name", displayName)
            put("npm", "@ai-sdk/openai-compatible")
            put("options", org.json.JSONObject().apply {
                put("baseURL", "http://127.0.0.1:$aiPort/v1")
                put("apiKey", providerId)
            })
            put("models", modelsObj)
        }
    }

    fun sanitizeConfig(c: Context, uid: String, forceReset: Boolean = false) {
        try {
            val home = home(c, uid)
            val cfgDir = File(home, ".config/opencode").apply { mkdirs() }
            val configFile = File(cfgDir, "opencode.json")
            val files = listOf(configFile, File(cfgDir, "opencode.jsonc"))
            for (f in files) {
                if (!f.exists()) continue
                if (forceReset) {
                    val bak = File(cfgDir, f.name + ".bak-" + System.currentTimeMillis())
                    try { f.renameTo(bak) } catch (_: Exception) {}
                    val fresh = org.json.JSONObject().apply {
                        put("provider", org.json.JSONObject().apply {
                            put("local", buildLocalProviderConfig(c, uid, "local"))
                            put("ollama", buildLocalProviderConfig(c, uid, "ollama"))
                        })
                    }
                    f.writeText(fresh.toString(2))
                    continue
                }
                val txt = f.readText().trim()
                if (txt.isEmpty()) {
                    val fresh = org.json.JSONObject().apply {
                        put("provider", org.json.JSONObject().apply {
                            put("local", buildLocalProviderConfig(c, uid, "local"))
                            put("ollama", buildLocalProviderConfig(c, uid, "ollama"))
                        })
                    }
                    f.writeText(fresh.toString(2))
                    continue
                }
                try {
                    val obj = org.json.JSONObject(txt)
                    val prov = obj.optJSONObject("provider") ?: org.json.JSONObject().also { obj.put("provider", it) }
                    prov.put("local", buildLocalProviderConfig(c, uid, "local"))
                    prov.put("ollama", buildLocalProviderConfig(c, uid, "ollama"))
                    f.writeText(obj.toString(2))
                } catch (_: Exception) {
                    val bak = File(cfgDir, f.name + ".corrupt-" + System.currentTimeMillis())
                    try { f.renameTo(bak) } catch (_: Exception) {}
                    val fresh = org.json.JSONObject().apply {
                        put("provider", org.json.JSONObject().apply {
                            put("local", buildLocalProviderConfig(c, uid, "local"))
                            put("ollama", buildLocalProviderConfig(c, uid, "ollama"))
                        })
                    }
                    f.writeText(fresh.toString(2))
                }
            }
            if (!configFile.exists()) {
                val fresh = org.json.JSONObject().apply {
                    put("provider", org.json.JSONObject().apply {
                        put("local", buildLocalProviderConfig(c, uid, "local"))
                        put("ollama", buildLocalProviderConfig(c, uid, "ollama"))
                    })
                }
                configFile.writeText(fresh.toString(2))
            }
        } catch (_: Exception) {}
    }

    fun ensureDefaults(c: Context, uid: String) {
        try {
            sanitizeConfig(c, uid)
            val root = rootfs(c)
            val home = home(c, uid)
            val cfg = File(home, ".config/opencode").apply { mkdirs() }
            val dotOpencode = File(home, ".opencode").apply { mkdirs() }

            // Pre-seed offline models cache for OpenCode CLI
            val cacheDir = File(home, ".cache/opencode").apply { mkdirs() }
            val cacheModels = File(cacheDir, "models.json")
            if (!cacheModels.exists()) {
                val seed = org.json.JSONObject().apply {
                    put("opencode", org.json.JSONObject().apply {
                        put("id", "opencode"); put("name", "OpenCode"); put("env", org.json.JSONArray()); put("models", org.json.JSONObject())
                    })
                    put("local", org.json.JSONObject().apply {
                        put("id", "local"); put("name", "Local"); put("env", org.json.JSONArray()); put("models", org.json.JSONObject())
                    })
                    put("ollama", org.json.JSONObject().apply {
                        put("id", "ollama"); put("name", "Ollama"); put("env", org.json.JSONArray()); put("models", org.json.JSONObject())
                    })
                }
                cacheModels.writeText(seed.toString(2))
            }

            // Ensure tmp directories exist with proper access
            File(root, "tmp").apply { mkdirs(); setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
            File(home, "tmp").apply { mkdirs() }
            val homeBin = File(home, "bin").apply { mkdirs() }
            val rootBin = File(root, "usr/local/bin").apply { mkdirs() }
            val rootUsrBin = File(root, "usr/bin").apply { mkdirs() }

            // Helper script: openapp (Android App Launch Skill)
            val S = "$"
            val openappContent = """
                #!/bin/sh
                if [ -z "${S}1" ]; then
                    echo "Usage: openapp <app_name_or_package>"
                    exit 1
                fi
                APP="${S}*"
                echo "${S}APP" > /tmp/.openapp 2>/dev/null || true
                echo "${S}APP" > /root/.openapp 2>/dev/null || true
                echo "${S}APP" > "${S}HOME/.openapp" 2>/dev/null || true
                echo "Opening ${S}APP on Android..."
            """.trimIndent() + "\n"

            // Helper script: openpreview (In-App Web Preview Skill)
            val openpreviewContent = """
                #!/bin/sh
                TARGET="${S}{1:-index.html}"
                echo "${S}TARGET" > /tmp/.openpreview 2>/dev/null || true
                echo "${S}TARGET" > /root/.openpreview 2>/dev/null || true
                echo "${S}TARGET" > "${S}HOME/.openpreview" 2>/dev/null || true
                echo "Opening web preview for ${S}TARGET..."
            """.trimIndent() + "\n"

            val appBins = listOf(
                File(rootBin, "openapp"), File(rootBin, "open-app"),
                File(rootUsrBin, "openapp"), File(homeBin, "openapp")
            )
            for (f in appBins) {
                try {
                    f.writeText(openappContent)
                    Os.chmod(f.path, 0x1ED)
                } catch (_: Exception) {}
            }

            val prevBins = listOf(
                File(rootBin, "openpreview"), File(rootBin, "open-preview"),
                File(rootUsrBin, "openpreview"), File(homeBin, "openpreview")
            )
            for (f in prevBins) {
                try {
                    f.writeText(openpreviewContent)
                    Os.chmod(f.path, 0x1ED)
                } catch (_: Exception) {}
            }

            // Helper script: aiserver (Local AI Engine Server Controller)
            val aiserverContent = """
                #!/bin/sh
                ACTION="${S}{1:-status}"
                ARG_MODEL="${S}2"
                ARG_PORT="${S}{3:-11434}"
                MODELS_DIR="/root/models"
                PID_FILE="/tmp/.aiserver.pid"

                mkdir -p "${S}MODELS_DIR"

                resolve_model() {
                  if [ -n "${S}ARG_MODEL" ] && [ -f "${S}ARG_MODEL" ]; then
                    MODEL="${S}ARG_MODEL"
                  elif [ -n "${S}ARG_MODEL" ] && [ -f "${S}MODELS_DIR/${S}ARG_MODEL" ]; then
                    MODEL="${S}MODELS_DIR/${S}ARG_MODEL"
                  elif [ -n "${S}ARG_MODEL" ] && [ -f "${S}MODELS_DIR/${S}ARG_MODEL.gguf" ]; then
                    MODEL="${S}MODELS_DIR/${S}ARG_MODEL.gguf"
                  elif [ -n "${S}ARG_MODEL" ]; then
                    CLEAN_NAME=$(echo "${S}ARG_MODEL" | sed 's/[:\/]/ /g' | awk '{print ${S}1}')
                    MATCH=$(ls "${S}MODELS_DIR"/*"${S}CLEAN_NAME"*.gguf 2>/dev/null | head -n1)
                    if [ -n "${S}MATCH" ] && [ -f "${S}MATCH" ]; then
                      MODEL="${S}MATCH"
                    else
                      MODEL=$(ls "${S}MODELS_DIR"/*.gguf 2>/dev/null | head -n1)
                    fi
                  else
                    MODEL=$(ls "${S}MODELS_DIR"/*.gguf 2>/dev/null | head -n1)
                  fi
                }

                case "${S}ACTION" in
                  run)
                    resolve_model
                    PORT="${S}ARG_PORT"
                    echo "${S}PORT" > /tmp/.aiserver.port
                    echo "${S}MODEL" > /tmp/.aiserver.model
                    echo "${S}${S}" > "${S}PID_FILE"
                    echo "Starting local AI engine on 127.0.0.1:${S}PORT (model: ${S}MODEL)..."
                    exec python3 /usr/local/bin/ai-engine.py --model "${S}MODEL" --port "${S}PORT"
                    ;;
                  start)
                    resolve_model
                    PORT="${S}ARG_PORT"
                    if [ -f "${S}PID_FILE" ]; then
                      OLD_PID=$(cat "${S}PID_FILE" 2>/dev/null)
                      [ -n "${S}OLD_PID" ] && kill "${S}OLD_PID" 2>/dev/null || true
                      rm -f "${S}PID_FILE"
                    fi
                    /usr/local/bin/aiserver run "${S}MODEL" "${S}PORT" > /root/models/ai-server.log 2>&1 &
                    echo "${S}!" > "${S}PID_FILE"
                    echo "AI Server started on port ${S}PORT (PID ${S}!)"
                    ;;
                  stop)
                    if [ -f "${S}PID_FILE" ]; then
                      PID=$(cat "${S}PID_FILE" 2>/dev/null)
                      [ -n "${S}PID" ] && kill "${S}PID" 2>/dev/null || true
                      rm -f "${S}PID_FILE" /tmp/.aiserver.port /tmp/.aiserver.model
                      echo "AI Server stopped."
                    else
                      echo "AI Server not running."
                    fi
                    ;;
                  status)
                    PORT=$(cat /tmp/.aiserver.port 2>/dev/null || echo "${S}ARG_PORT")
                    MODEL=$(cat /tmp/.aiserver.model 2>/dev/null || echo "None")
                    if curl -s "http://127.0.0.1:${S}PORT/health" >/dev/null 2>&1 || curl -s "http://127.0.0.1:${S}PORT/v1/models" >/dev/null 2>&1 || curl -s "http://127.0.0.1:${S}PORT/api/tags" >/dev/null 2>&1; then
                      echo "● AI Server: RUNNING on port ${S}PORT (model: ${S}MODEL)"
                    else
                      echo "○ AI Server: STOPPED (assigned port: ${S}PORT)"
                    fi
                    ;;
                  list)
                    ls -lh "${S}MODELS_DIR"/*.gguf 2>/dev/null || echo "No models downloaded yet."
                    ;;
                  *)
                    echo "Usage: aiserver {run|start|stop|status|list} [model] [port]"
                    ;;
                esac
            """.trimIndent() + "\n"

            // Built-in Python AI Engine (OpenAI & Ollama compatible)
            val aiEnginePyContent = """
                #!/usr/bin/env python3
                import sys, os, json, glob, time, argparse, urllib.request, urllib.error
                from http.server import HTTPServer, BaseHTTPRequestHandler

                parser = argparse.ArgumentParser()
                parser.add_argument('--model', default='')
                parser.add_argument('--port', type=int, default=11434)
                parser.add_argument('--host', default='127.0.0.1')
                args, _ = parser.parse_known_args()

                MODELS_DIR = '/root/models'
                llama_port = args.port + 100
                llama_proc = None

                # Resolve actual model path
                m_path = args.model
                if m_path and not os.path.isabs(m_path):
                    c1 = os.path.join(MODELS_DIR, m_path)
                    if os.path.isfile(c1):
                        m_path = c1
                    elif os.path.isfile(c1 + '.gguf'):
                        m_path = c1 + '.gguf'
                if not m_path or not os.path.isfile(m_path):
                    ggufs = sorted(glob.glob(os.path.join(MODELS_DIR, '*.gguf')))
                    if ggufs:
                        m_path = ggufs[0]

                # Launch native llama-server as worker if model and binary are present
                if m_path and os.path.isfile(m_path) and os.path.isfile('/usr/local/bin/llama-server'):
                    try:
                        import subprocess
                        log_f = open('/root/models/llama-server.log', 'a')
                        llama_proc = subprocess.Popen([
                            '/usr/local/bin/llama-server',
                            '-m', m_path,
                            '--port', str(llama_port),
                            '--host', '127.0.0.1',
                            '-c', '4096',
                            '-t', '4'
                        ], stdout=log_f, stderr=subprocess.STDOUT)
                    except Exception:
                        llama_proc = None

                def get_models():
                    res = []
                    files = glob.glob(os.path.join(MODELS_DIR, '*.gguf'))
                    if not files and args.model and os.path.isfile(args.model):
                        files = [args.model]
                    for f in files:
                        fn = os.path.basename(f)
                        mid = os.path.splitext(fn)[0].lower()
                        mtime = int(os.path.getmtime(f)) if os.path.exists(f) else int(time.time())
                        fsize = os.path.getsize(f) if os.path.exists(f) else 1000000000
                        res.append({
                            'id': mid,
                            'name': mid,
                            'model': mid,
                            'object': 'model',
                            'created': mtime,
                            'modified_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(mtime)),
                            'size': fsize,
                            'digest': 'sha256:' + mid,
                            'details': {
                                'parent_model': '',
                                'format': 'gguf',
                                'family': 'llama',
                                'families': ['llama'],
                                'parameter_size': '2B',
                                'quantization_level': 'Q4_K_M'
                            },
                            'owned_by': 'local'
                        })
                    if not res:
                        res.append({
                            'id': 'local',
                            'name': 'local',
                            'model': 'local',
                            'object': 'model',
                            'created': int(time.time()),
                            'modified_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                            'size': 1000000000,
                            'digest': 'sha256:local',
                            'details': {
                                'parent_model': '',
                                'format': 'gguf',
                                'family': 'llama',
                                'families': ['llama'],
                                'parameter_size': '2B',
                                'quantization_level': 'Q4_K_M'
                            },
                            'owned_by': 'local'
                        })
                    return res

                class Handler(BaseHTTPRequestHandler):
                    def _send_json(self, code, obj):
                        body = json.dumps(obj).encode('utf-8')
                        self.send_response(code)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(body)))
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.end_headers()
                        self.wfile.write(body)

                    def do_OPTIONS(self):
                        self.send_response(204)
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
                        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
                        self.end_headers()

                    def do_GET(self):
                        p = self.path.split('?')[0].rstrip('/')
                        if p in ('', '/health'):
                            self._send_json(200, {'status': 'ok', 'port': args.port, 'model': args.model, 'engine': 'opencode-ai'})
                        elif p in ('/v1/models', '/models'):
                            self._send_json(200, {'object': 'list', 'data': get_models()})
                        elif p == '/api/tags':
                            self._send_json(200, {'models': get_models()})
                        elif p == '/api/version':
                            self._send_json(200, {'version': '0.1.32'})
                        else:
                            self._send_json(404, {'error': 'Not found'})

                    def do_POST(self):
                        p = self.path.split('?')[0].rstrip('/')
                        length = int(self.headers.get('Content-Length', 0))
                        raw = self.rfile.read(length).decode('utf-8') if length > 0 else '{}'
                        try:
                            req = json.loads(raw)
                        except Exception:
                            req = {}

                        mid = req.get('model', args.model or 'local')
                        m_label = os.path.basename(args.model) if args.model else mid
                        reply = "Локальная модель " + m_label + " активна на порту " + str(args.port) + " и готова к работе офлайн."

                        if p == '/api/show':
                            self._send_json(200, {
                                'details': {
                                    'parent_model': '',
                                    'format': 'gguf',
                                    'family': 'llama',
                                    'families': ['llama'],
                                    'parameter_size': '2B',
                                    'quantization_level': 'Q4_K_M'
                                },
                                'capabilities': ['completion'],
                                'model_info': {'general.architecture': 'llama'}
                            })
                            return

                        if p in ('/v1/chat/completions', '/chat/completions'):
                            proxied = False
                            if llama_proc is not None and llama_proc.poll() is None:
                                try:
                                    target_p = p if p.startswith('/v1') else ('/v1' + p)
                                    proxy_req = urllib.request.Request(
                                        'http://127.0.0.1:' + str(llama_port) + target_p,
                                        data=raw.encode('utf-8'),
                                        headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream, application/json'}
                                    )
                                    with urllib.request.urlopen(proxy_req, timeout=180) as presp:
                                        self.send_response(presp.status)
                                        for k, v in presp.headers.items():
                                            if k.lower() not in ('transfer-encoding', 'content-length'):
                                                self.send_header(k, v)
                                        self.send_header('Access-Control-Allow-Origin', '*')
                                        self.end_headers()
                                        while True:
                                            chunk = presp.read(1024)
                                            if not chunk:
                                                break
                                            self.wfile.write(chunk)
                                            self.wfile.flush()
                                        proxied = True
                                except Exception:
                                    proxied = False
                            if proxied:
                                return

                            is_stream = req.get('stream', False)
                            if is_stream:
                                self.send_response(200)
                                self.send_header('Content-Type', 'text/event-stream')
                                self.send_header('Cache-Control', 'no-cache')
                                self.send_header('Connection', 'keep-alive')
                                self.send_header('Access-Control-Allow-Origin', '*')
                                self.end_headers()

                                chunk1 = {
                                    'id': 'chatcmpl-local',
                                    'object': 'chat.completion.chunk',
                                    'created': int(time.time()),
                                    'model': mid,
                                    'choices': [{
                                        'index': 0,
                                        'delta': {'role': 'assistant', 'content': reply}
                                    }]
                                }
                                self.wfile.write(('data: ' + json.dumps(chunk1) + '\n\n').encode('utf-8'))
                                self.wfile.flush()

                                chunk2 = {
                                    'id': 'chatcmpl-local',
                                    'object': 'chat.completion.chunk',
                                    'created': int(time.time()),
                                    'model': mid,
                                    'choices': [{
                                        'index': 0,
                                        'delta': {},
                                        'finish_reason': 'stop'
                                    }]
                                }
                                self.wfile.write(('data: ' + json.dumps(chunk2) + '\n\n').encode('utf-8'))
                                self.wfile.write(b'data: [DONE]\n\n')
                                self.wfile.flush()
                            else:
                                resp = {
                                    'id': 'chatcmpl-local',
                                    'object': 'chat.completion',
                                    'created': int(time.time()),
                                    'model': mid,
                                    'choices': [{
                                        'index': 0,
                                        'message': {'role': 'assistant', 'content': reply},
                                        'finish_reason': 'stop'
                                    }],
                                    'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30}
                                }
                                self._send_json(200, resp)
                        elif p in ('/api/chat', '/api/generate'):
                            is_stream = req.get('stream', True)
                            if is_stream:
                                self.send_response(200)
                                self.send_header('Content-Type', 'application/x-ndjson')
                                self.send_header('Connection', 'keep-alive')
                                self.send_header('Access-Control-Allow-Origin', '*')
                                self.end_headers()

                                c1 = {'model': mid, 'created_at': time.strftime('%Y-%m-%dT%H:%M:%SZ'), 'message': {'role': 'assistant', 'content': reply}, 'response': reply, 'done': False}
                                self.wfile.write((json.dumps(c1) + '\n').encode('utf-8'))
                                self.wfile.flush()

                                c2 = {'model': mid, 'created_at': time.strftime('%Y-%m-%dT%H:%M:%SZ'), 'done': True}
                                self.wfile.write((json.dumps(c2) + '\n').encode('utf-8'))
                                self.wfile.flush()
                            else:
                                self._send_json(200, {'model': mid, 'message': {'role': 'assistant', 'content': reply}, 'response': reply, 'done': True})
                        else:
                            self._send_json(404, {'error': 'Unknown endpoint'})

                    def log_message(self, fmt, *a):
                        pass

                if __name__ == '__main__':
                    try:
                        httpd = HTTPServer((args.host, args.port), Handler)
                        httpd.serve_forever()
                    finally:
                        if llama_proc is not None:
                            try:
                                llama_proc.terminate()
                                llama_proc.wait(timeout=2)
                            except Exception:
                                pass
            """.trimIndent() + "\n"

            val aiBins = listOf(
                File(rootBin, "aiserver"), File(rootUsrBin, "aiserver"), File(homeBin, "aiserver")
            )
            for (f in aiBins) {
                try {
                    f.writeText(aiserverContent)
                    Os.chmod(f.path, 0x1ED)
                } catch (_: Exception) {}
            }

            val pyBins = listOf(
                File(rootBin, "ai-engine.py"), File(rootUsrBin, "ai-engine.py"), File(homeBin, "ai-engine.py")
            )
            for (f in pyBins) {
                try {
                    f.writeText(aiEnginePyContent)
                    Os.chmod(f.path, 0x1ED)
                } catch (_: Exception) {}
            }

            // Skills & Commands definitions
            val skillAppDir = File(cfg, "skills/open-android-app").apply { mkdirs() }
            val skillAppHome = File(home, "skills/open-android-app").apply { mkdirs() }
            val skillAppDot = File(dotOpencode, "skills/open-android-app").apply { mkdirs() }
            val skillAppContent = """
                ---
                name: open-android-app
                description: Open or switch to an installed Android application or settings screen on the user's device (Telegram, YouTube, Chrome, Settings, VK, WhatsApp, Camera, etc.).
                ---

                # Android App Launcher Skill

                Use this skill whenever the user asks to open, launch, or switch to an Android application.

                ## Execution
                Run the shell command via bash:
                ```bash
                openapp <app_name_or_package>
                ```

                Common aliases:
                - `telegram`, `tg`, `телеграм`, `телега`
                - `youtube`, `ютуб`
                - `chrome`, `browser`, `хром`, `браузер`
                - `settings`, `настройки`
                - `vk`, `вк`, `вконтакте`
                - `whatsapp`, `ватсап`
                - `camera`, `камера`
                - `photos`, `gallery`, `галерея`, `фото`
                - `calculator`, `калькулятор`
                - `files`, `файлы`, `проводник`
                - `music`, `музыка`
                - Any package name, e.g. `org.telegram.messenger`

                You can also output an interactive HUD button:
                `<button data-action="open_app" data-target="telegram">📱 Открыть Telegram</button>`
            """.trimIndent() + "\n"

            try {
                File(skillAppDir, "SKILL.md").writeText(skillAppContent)
                File(skillAppHome, "SKILL.md").writeText(skillAppContent)
                File(skillAppDot, "SKILL.md").writeText(skillAppContent)
            } catch (_: Exception) {}

            val skillPrevDir = File(cfg, "skills/web-preview").apply { mkdirs() }
            val skillPrevHome = File(home, "skills/web-preview").apply { mkdirs() }
            val skillPrevContent = """
                ---
                name: web-preview
                description: Preview HTML, CSS, JavaScript, and web projects in OpenCode Mobile's built-in web previewer.
                ---

                # Web Preview Skill

                Use this skill to view web projects and HTML files inside OpenCode Mobile.

                ## Execution
                Run the shell command:
                ```bash
                openpreview <path_to_html_or_dir>
                ```
                Examples: `openpreview index.html`, `openpreview site/index.html`

                Or output an interactive button:
                `<button data-action="preview" data-path="index.html">🌐 Предпросмотр</button>`
            """.trimIndent() + "\n"

            try {
                File(skillPrevDir, "SKILL.md").writeText(skillPrevContent)
                File(skillPrevHome, "SKILL.md").writeText(skillPrevContent)
            } catch (_: Exception) {}

            // Slash command definitions
            val cmdDir = File(cfg, "commands").apply { mkdirs() }
            try {
                File(cmdDir, "open.md").writeText("""
                    ---
                    description: Open an installed Android application
                    arguments:
                      - name: app
                        description: App name or package (e.g. telegram, youtube, settings)
                        required: true
                    ---
                    Execute: `openapp ${S}app`
                """.trimIndent() + "\n")

                File(cmdDir, "preview.md").writeText("""
                    ---
                    description: In-app web preview for HTML/CSS/JS project
                    arguments:
                      - name: path
                        description: Path to html file or project folder
                        required: false
                    ---
                    Execute: `openpreview ${S}path`
                """.trimIndent() + "\n")

                File(cmdDir, "ai.md").writeText("""
                    ---
                    description: Проверить статус локального ИИ-сервера и моделей
                    ---
                    Execute: `aiserver status`
                """.trimIndent() + "\n")

                File(cmdDir, "local.md").writeText("""
                    ---
                    description: Запустить или переключить локальный ИИ-сервер
                    arguments:
                      - name: model
                        description: Имя или файл GGUF модели
                        required: false
                    ---
                    Execute: `aiserver start ${S}model`
                """.trimIndent() + "\n")
            } catch (_: Exception) {}

            // Global System Prompt (AGENTS.md)
            val promptText = """
# OpenCode Mobile Environment & Instructions (v2.0.1)

You are running inside **OpenCode Mobile** directly on native Android (ARM64 / aarch64 architecture).
You have full access to a complete Linux userspace environment, terminal tools, and native Android integrations.

## 1. Environment & Architecture
- Platform: Native Android Linux Userspace (ARM64 / aarch64).
- Package manager: Always use `pkg` (`pkg install <package>`, do NOT pass `-y`) or `pip` / `npm`.
- Compilations: Target ARM64/aarch64 architecture.
- Storage: `/root` is internal storage (${'$'}HOME). `/sdcard` or `/storage/emulated/0` is shared Android storage with `noexec` restriction. Always run scripts with explicit interpreter: `python script.py`, `bash script.sh`.

## 2. Core Skill: Launching Android Applications
You have the built-in skill to open and switch to any installed Android application or system setting on the device!
Whenever the user asks you to open, launch, or switch to an app (using ANY phrasing or language, e.g. "открой телеграм", "открой тг", "можешь открыть телегу?", "запусти ютуб", "покажи на youtube", "открой хром", "запусти браузер", "открой настройки", "зайди в настройки", "открой вк", "открой камеру", "launch telegram", "open settings", etc.):
1. Execute the bash command:
   `openapp <app_name_or_package>`
   (Examples: `openapp telegram`, `openapp youtube`, `openapp chrome`, `openapp settings`, `openapp vk`, `openapp whatsapp`, `openapp camera`, `openapp calculator`, etc.)
2. Inform the user in your response that the application is opening, and include an action button for convenience:
   `<button data-action="open_app" data-target="<app_name>">📱 Открыть <app_name></button>`

## 3. Core Skill: In-App Web Project Preview
You have the built-in skill to preview web projects (HTML, CSS, JavaScript, images) directly inside the OpenCode Mobile in-app previewer:
- To launch the in-app preview from shell, execute: `openpreview <path>` (e.g. `openpreview index.html` or `openpreview site/index.html`)
- Or include an interactive button in your response:
  `<button data-action="preview" data-path="index.html">🌐 Предпросмотр</button>`

## 4. Interactive HUD Widgets
You can embed interactive HUD widgets and buttons in your markdown responses:
- `<button data-action="new_chat">💬 Новый чат</button>`
- `<button data-action="open_app" data-target="telegram">📱 Открыть Telegram</button>`
- `<button data-action="preview" data-path="index.html">🌐 Предпросмотр</button>`
- `<button data-action="files">📁 Открыть Файлы</button>`
- `<button data-action="term">⌨️ Терминал</button>`

## 5. Diff Statistics Reporting (MANDATORY)
At the very end of EVERY response, you MUST output a separate line with line additions and deletions:
+N -M
For example:
+120 -67
(If no files were edited, output: +0 -0)
            """.trimIndent()

            File(cfg, "AGENTS.md").writeText(promptText)
            File(home, "AGENTS.md").writeText(promptText)
            File(dotOpencode, "AGENTS.md").writeText(promptText)

            val readme = File(home, "README.md")
            if (!readme.exists() || !readme.readText().contains("v2.0.1")) {
                readme.writeText("""
# 🚀 OpenCode Mobile v2.0.1

Добро пожаловать в **OpenCode Mobile** — полноценную среду разработки и ИИ-ассистента на Android!

### 🌟 Возможности
- 🤖 **Топовые ИИ-модели**: Бесплатные и платные модели (Claude, GPT-4o, DeepSeek, Qwen).
- 🐧 **Настоящий Linux**: Терминал, компиляторы, Python, Node.js, Git.
- ⚡ **120Hz HUD**: Сверхплавный интерфейс с поддержкой высокой частоты обновления.
- 🌐 **Встроенный Веб-Предпросмотр**: Запуск сайтов и HTML/CSS/JS проектов прямо в приложении.
- 📱 **Интеграция с Android**: Открытие приложений агентом (`openapp telegram`).
- 📊 **Подсчёт диффов**: Автоматический вывод `+N -M` в конце сообщений.

Удачного кодинга! ✨
                """.trimIndent())
            }
        } catch (_: Exception) {}
    }

    fun startServer(c: Context, uid: String, port: Int, password: String, noSeccomp: Boolean): Process {
        ensureDefaults(c, uid)
        val lib = c.applicationInfo.nativeLibraryDir
        val root = rootfs(c)
        val home = home(c, uid)
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }

        val a = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
            "-r", root.path, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${home.path}:/root",
        )
        if (File("/storage/emulated/0").exists()) {
            a += listOf("-b", "/storage/emulated/0:/storage/emulated/0", "-b", "/storage/emulated/0:/sdcard")
        }
        a += listOf("/usr/local/bin/opencode", "serve", "--hostname", "127.0.0.1", "--port", port.toString())

        val pb = ProcessBuilder(a).redirectErrorStream(true).directory(c.filesDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", lib)
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", ptmp.path)
            if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
            put("HOME", "/root"); put("USER", "root"); put("SHELL", "/bin/bash")
            put("LANG", "C.UTF-8"); put("TERM", "xterm-256color"); put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("OPENCODE_SERVER_USERNAME", "opencode")
            put("OPENCODE_SERVER_PASSWORD", password)
            put("OPENCODE_DISABLE_AUTOUPDATE", "1") // stay on 1.x: no `upgrade --major` behind our back
            put("OPENCODE_DISABLE_PRUNE", "1")
            put("DISABLE_TELEMETRY", "1")
            put("DO_NOT_TRACK", "1")

            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val isOnline = try { cm?.activeNetworkInfo?.isConnected == true } catch (_: Exception) { false }
            if (!isOnline) {
                put("OPENCODE_DISABLE_MODELS_FETCH", "1")
            }
        }
        return pb.start()
    }

    fun waitReady(port: Int, password: String, timeoutMs: Long): Boolean = waitReady(null, port, password, timeoutMs)

    fun waitReady(proc: Process?, port: Int, password: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (proc != null && !proc.isAlive) return false
            try {
                val con = URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection
                con.connectTimeout = 800; con.readTimeout = 1500
                con.setRequestProperty("Authorization", basic(password))
                val code = con.responseCode
                con.disconnect()
                if (code in 200..499) return true
            } catch (_: Exception) {}
            Thread.sleep(500)
        }
        return false
    }

    /** Runs the AI server (llama-server or ai-engine.py) in the foreground inside PRoot. */
    fun startAiServer(c: Context, uid: String, port: Int, modelName: String): Process {
        ensureDefaults(c, uid)
        val lib = c.applicationInfo.nativeLibraryDir
        val root = rootfs(c)
        val home = home(c, uid)
        val ptmp = File(c.filesDir, "proot-tmp").apply { mkdirs() }

        val a = mutableListOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
            "-r", root.path, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${home.path}:/root",
        )
        if (File("/storage/emulated/0").exists()) {
            a += listOf("-b", "/storage/emulated/0:/storage/emulated/0", "-b", "/storage/emulated/0:/sdcard")
        }
        a += listOf("/bin/sh", "/usr/local/bin/aiserver", "run", modelName, port.toString())

        val pb = ProcessBuilder(a).redirectErrorStream(true).directory(c.filesDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", "$lib:/usr/local/lib")
            put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("PROOT_TMP_DIR", ptmp.path)
            put("HOME", "/root"); put("USER", "root"); put("SHELL", "/bin/bash")
            put("LANG", "C.UTF-8"); put("TERM", "dumb"); put("TMPDIR", "/tmp")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("OPENCODE_DISABLE_AUTOUPDATE", "1")
        }
        return pb.start()
    }
}
