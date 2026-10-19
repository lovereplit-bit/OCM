package dev.opencode.mobile

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * What a published update says about itself. The manifest is signed as raw bytes, so [parse] is only called on bytes
 * that already passed [OtaVerifier.verifySignature].
 */
data class OtaManifest(
    val schema: Int,
    val channel: String,
    val id: String,
    /** Monotonic build time in seconds. An update is only accepted when it is newer than what is installed. */
    val seq: Long,
    val upstreamRepo: String,
    val upstreamVersion: String,
    val upstreamRef: String,
    val minAppVersionCode: Int,
    val abi: String,
    val runtime: Asset?,
    val app: AppAsset?,
) {
    data class Asset(val name: String, val size: Long, val sha256: String, val entry: String)

    data class AppAsset(val name: String, val size: Long, val sha256: String, val versionCode: Int)

    companion object {
        const val SCHEMA = 1
        const val MAX_RUNTIME_BYTES = 600L * 1024 * 1024
        const val MAX_APP_BYTES = 1200L * 1024 * 1024

        private val SHA = Regex("^[0-9a-f]{64}$")
        private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun parse(bytes: ByteArray): OtaManifest {
            val root = MiniJson.obj(bytes.toString(Charsets.UTF_8)) ?: throw OtaException("Manifest is not JSON")
            val schema = (root["schema"] as? Number)?.toInt() ?: throw OtaException("Manifest has no schema")

            if (schema != SCHEMA) throw OtaException("Unsupported manifest schema $schema")

            val id = root["id"] as? String ?: throw OtaException("Manifest has no id")

            if (!ID.matches(id)) throw OtaException("Bad update id")

            val upstream = root["upstream"] as? Map<*, *> ?: throw OtaException("Manifest has no upstream block")
            val runtime = (root["runtime"] as? Map<*, *>)?.let { asset(it, MAX_RUNTIME_BYTES) }
            val app = (root["app"] as? Map<*, *>)?.let { appAsset(it) }

            if (runtime == null && app == null) throw OtaException("Manifest carries nothing to install")

            return OtaManifest(
                schema = schema,
                channel = root["channel"] as? String ?: "stable",
                id = id,
                seq = (root["seq"] as? Number)?.toLong() ?: throw OtaException("Manifest has no seq"),
                upstreamRepo = upstream["repo"] as? String ?: "",
                upstreamVersion = upstream["version"] as? String ?: throw OtaException("Manifest has no upstream version"),
                upstreamRef = upstream["ref"] as? String ?: "",
                minAppVersionCode = (root["minAppVersionCode"] as? Number)?.toInt() ?: 0,
                abi = root["abi"] as? String ?: "arm64-v8a",
                runtime = runtime,
                app = app,
            )
        }

        private fun asset(map: Map<*, *>, limit: Long): Asset {
            val name = map["name"] as? String ?: throw OtaException("Asset has no name")
            val size = (map["size"] as? Number)?.toLong() ?: throw OtaException("Asset has no size")
            val sha = (map["sha256"] as? String)?.lowercase() ?: throw OtaException("Asset has no checksum")
            val entry = map["entry"] as? String ?: "opencode"

            if (!NAME.matches(name) || !NAME.matches(entry)) throw OtaException("Bad asset name")
            if (!SHA.matches(sha)) throw OtaException("Bad checksum")
            if (size <= 0 || size > limit) throw OtaException("Asset size is out of range")

            return Asset(name, size, sha, entry)
        }

        private fun appAsset(map: Map<*, *>): AppAsset {
            val base = asset(map, MAX_APP_BYTES)
            val code = (map["versionCode"] as? Number)?.toInt() ?: throw OtaException("App asset has no versionCode")

            return AppAsset(base.name, base.size, base.sha256, code)
        }
    }
}

class OtaException(message: String) : Exception(message)

/** Why a published update was not offered. Shown in diagnostics; never fatal. */
enum class Skip { NOT_NEWER, NEEDS_NEWER_APP, WRONG_ABI, BLOCKED, WRONG_CHANNEL }

object OtaVerifier {
    /** The algorithm follows the key that signed the APK: RSA for the default keystore, ECDSA for an EC one. */
    fun algorithm(key: PublicKey): String = when (key.algorithm) {
        "RSA" -> "SHA256withRSA"
        "EC" -> "SHA256withECDSA"
        else -> throw OtaException("Unsupported signing key ${key.algorithm}")
    }

    /** [signature] is raw DER/PKCS#1 bytes, or base64 text of them (what `openssl base64` produces). */
    fun verifySignature(manifest: ByteArray, signature: ByteArray, keys: List<PublicKey>): Boolean {
        val raw = decode(signature)

        for (key in keys) {
            val ok = runCatching {
                Signature.getInstance(algorithm(key)).run {
                    initVerify(key)
                    update(manifest)
                    verify(raw)
                }
            }.getOrDefault(false)

            if (ok) return true
        }

        return false
    }

    private fun decode(signature: ByteArray): ByteArray {
        val text = signature.toString(Charsets.ISO_8859_1).trim()

        if (text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' || it == '\r' }) {
            val decoded = runCatching { Base64.getMimeDecoder().decode(text) }.getOrNull()

            if (decoded != null && decoded.size >= 64) return decoded
        }

        return signature
    }

    /** Hashes [input] while copying it to [sink]; fails if more than [limit] bytes arrive. Returns hex SHA-256 and length. */
    fun copyHashed(input: InputStream, sink: java.io.OutputStream, limit: Long, onBytes: (Long) -> Unit = {}): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L

        while (true) {
            val n = input.read(buffer)

            if (n < 0) break

            total += n

            if (total > limit) throw OtaException("Download is larger than announced")

            digest.update(buffer, 0, n)
            sink.write(buffer, 0, n)
            onBytes(total)
        }

        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }
}

/** Decides whether a verified manifest is worth offering. Pure, so every rule is covered by tests. */
/** A verified manifest together with the release it came from (the files to download are attached to that release). */
data class Offer(val manifest: OtaManifest, val release: ReleaseInfo)

/** What a check found: the best server build and the best app build, which may come from different releases. */
data class Picks(val runtime: Offer?, val app: Offer?)

object OtaPolicy {
    /**
     * Chooses from releases whose manifests are already verified. A server build and an app build are chosen
     * independently, so a later server-only release never hides an app update that is still pending.
     */
    fun pick(verified: List<Offer>, installedSeq: Long, appVersionCode: Int, blocked: Set<String>, channel: String = "stable", abi: String = "arm64-v8a"): Picks {
        val runtime = verified
            .filter { it.manifest.runtime != null && check(it.manifest, installedSeq, appVersionCode, blocked, channel, abi) == null }
            .maxByOrNull { it.manifest.seq }
        val app = verified
            .filter {
                val a = it.manifest.app

                a != null && a.versionCode > appVersionCode && it.manifest.abi == abi && it.manifest.channel == channel && it.manifest.id !in blocked
            }
            .maxByOrNull { it.manifest.app!!.versionCode }

        return Picks(runtime, app)
    }

    fun check(
        manifest: OtaManifest,
        installedSeq: Long,
        appVersionCode: Int,
        blocked: Set<String>,
        channel: String = "stable",
        abi: String = "arm64-v8a",
    ): Skip? {
        if (manifest.channel != channel) return Skip.WRONG_CHANNEL
        if (manifest.abi != abi) return Skip.WRONG_ABI
        if (manifest.id in blocked) return Skip.BLOCKED
        if (manifest.seq <= installedSeq) return Skip.NOT_NEWER
        if (manifest.minAppVersionCode > appVersionCode) return Skip.NEEDS_NEWER_APP

        return null
    }
}

/**
 * Which runtime builds are on disk and which one is in use. Persisted as JSON; the files live in `versions/<id>/`.
 * `current` is the runtime the server starts with, `previous` what to return to after a failed start, `staged` an
 * update that is downloaded and checked but not yet in use.
 */
class OtaState(private val file: File) {
    var current: String? = null
        private set

    var currentSeq: Long = 0
        private set

    var currentVersion: String? = null
        private set

    var previous: String? = null
        private set

    var previousSeq: Long = 0
        private set

    var previousVersion: String? = null
        private set

    var staged: String? = null
        private set

    var stagedSeq: Long = 0
        private set

    var stagedVersion: String? = null
        private set

    /** The build that was switched to but has not yet proven itself with a healthy start. */
    var trial: String? = null
        private set

    var blocked: Set<String> = emptySet()
        private set

    var lastCheck: Long = 0
        private set

    init {
        load()
    }

    /** Seq of the runtime that will run: the OTA build when it is newer than the one bundled in the APK. */
    fun effectiveSeq(bundledSeq: Long) = if (current != null && currentSeq > bundledSeq) currentSeq else bundledSeq

    /** True when the bundled runtime is at least as new as the stored one, so the stored one is stale. */
    fun supersededBy(bundledSeq: Long) = current != null && currentSeq <= bundledSeq

    fun stage(id: String, seq: Long, version: String) {
        staged = id
        stagedSeq = seq
        stagedVersion = version
        save()
    }

    fun clearStaged() {
        staged = null
        stagedSeq = 0
        stagedVersion = null
        save()
    }

    /** Switches to the staged build, remembering what to return to. Nothing happens without a staged build. */
    fun promote(): Boolean {
        val id = staged ?: return false

        previous = current
        previousSeq = currentSeq
        previousVersion = currentVersion
        current = id
        currentSeq = stagedSeq
        currentVersion = stagedVersion
        trial = id
        staged = null
        stagedSeq = 0
        stagedVersion = null
        save()

        return true
    }

    /** The trial build started and answered; it becomes the one to trust. */
    fun confirm() {
        if (trial == null) return

        trial = null
        save()
    }

    /** The trial build failed to start: go back and refuse that id from now on. Returns the id that was dropped. */
    fun rollback(): String? {
        val bad = trial ?: return null

        blocked = blocked + bad
        current = previous
        currentSeq = previousSeq
        currentVersion = previousVersion
        previous = null
        previousSeq = 0
        previousVersion = null
        trial = null
        save()

        return bad
    }

    fun forgetCurrent() {
        current = null
        currentSeq = 0
        currentVersion = null
        previous = null
        previousSeq = 0
        previousVersion = null
        trial = null
        save()
    }

    fun block(id: String) {
        blocked = blocked + id
        save()
    }

    fun checked(now: Long) {
        lastCheck = now
        save()
    }

    /** Builds that must stay on disk. */
    fun keep(): Set<String> = listOfNotNull(current, previous, staged).toSet()

    private fun load() {
        val root = if (file.exists()) runCatching { MiniJson.obj(file.readText()) }.getOrNull() else null

        if (root == null) return

        current = root["current"] as? String
        currentSeq = (root["currentSeq"] as? Number)?.toLong() ?: 0
        currentVersion = root["currentVersion"] as? String
        previous = root["previous"] as? String
        previousSeq = (root["previousSeq"] as? Number)?.toLong() ?: 0
        previousVersion = root["previousVersion"] as? String
        staged = root["staged"] as? String
        stagedSeq = (root["stagedSeq"] as? Number)?.toLong() ?: 0
        stagedVersion = root["stagedVersion"] as? String
        trial = root["trial"] as? String
        blocked = (root["blocked"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        lastCheck = (root["lastCheck"] as? Number)?.toLong() ?: 0
    }

    private fun save() {
        fun s(value: String?) = if (value == null) "null" else MiniJson.quote(value)

        val out = StringBuilder("{")

        out.append("\"current\":").append(s(current))
        out.append(",\"currentSeq\":").append(currentSeq)
        out.append(",\"currentVersion\":").append(s(currentVersion))
        out.append(",\"previous\":").append(s(previous))
        out.append(",\"previousSeq\":").append(previousSeq)
        out.append(",\"previousVersion\":").append(s(previousVersion))
        out.append(",\"staged\":").append(s(staged))
        out.append(",\"stagedSeq\":").append(stagedSeq)
        out.append(",\"stagedVersion\":").append(s(stagedVersion))
        out.append(",\"trial\":").append(s(trial))
        out.append(",\"blocked\":[").append(blocked.joinToString(",") { MiniJson.quote(it) }).append(']')
        out.append(",\"lastCheck\":").append(lastCheck)
        out.append('}')

        file.parentFile?.mkdirs()

        val temp = File(file.parentFile, file.name + ".tmp")

        temp.writeText(out.toString())

        if (!temp.renameTo(file)) {
            file.writeText(out.toString())
            temp.delete()
        }
    }
}

/** One release of the update repository as the GitHub API describes it. */
data class ReleaseInfo(val tag: String, val publishedAt: String, val assets: Map<String, String>)

object GithubReleases {
    const val TAG_PREFIX = "ota-"

    /** Update releases only: published, not a pre-release, tag `ota-*`. Newest first. Drafts never appear on this API. */
    fun parse(json: String): List<ReleaseInfo> {
        val list = MiniJson.parseOrNull(json) as? List<*> ?: return emptyList()
        val out = ArrayList<ReleaseInfo>()

        for (item in list) {
            val release = item as? Map<*, *> ?: continue
            val tag = release["tag_name"] as? String ?: continue

            if (!tag.startsWith(TAG_PREFIX)) continue
            if (release["draft"] == true || release["prerelease"] == true) continue

            val assets = LinkedHashMap<String, String>()

            for (asset in release["assets"] as? List<*> ?: emptyList<Any?>()) {
                val a = asset as? Map<*, *> ?: continue
                val name = a["name"] as? String ?: continue
                val url = a["browser_download_url"] as? String ?: continue

                if (isTrustedDownload(url)) assets[name] = url
            }

            out += ReleaseInfo(tag, release["published_at"] as? String ?: "", assets)
        }

        return out.sortedByDescending { it.publishedAt }
    }

    /** Assets must come from github.com over https; anything else in a release listing is ignored. */
    fun isTrustedDownload(url: String): Boolean {
        val uri = Urls.parse(url) ?: return false

        return uri.scheme == "https" && uri.host.equals("github.com", true)
    }

    fun valid(repo: String): Boolean {
        if (!Regex("^[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}$").matches(repo)) return false

        val (owner, name) = repo.split('/')

        return !owner.startsWith('.') && name != "." && name != ".."
    }
}
