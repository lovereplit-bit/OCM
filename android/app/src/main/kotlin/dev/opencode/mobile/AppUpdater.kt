package dev.opencode.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Updates the app itself in place. The APK is checked against the signed manifest (size and SHA-256), must carry the
 * same signing certificate as the installed app, and is handed to Android's package installer, which keeps all app
 * data. Android asks the person to confirm unless it is allowed to update silently.
 */
class AppUpdater(private val context: Context, private val paths: Paths, private val ota: OtaManager) {
    fun download(update: Offer, progress: (Int) -> Unit): File {
        val asset = update.manifest.app ?: throw OtaException("This release has no app update")
        val url = update.release.assets[asset.name] ?: throw OtaException("Release has no file ${asset.name}")
        val dest = File(paths.otaStaging, "app-${update.manifest.id}.apk")

        if (paths.ota.usableSpace < asset.size * 3) {
            throw OtaException(tr("Недостаточно места для обновления", "Not enough free space for the update"))
        }

        ota.fetch(url, asset.size, asset.sha256, dest, progress)

        try {
            requireSameApp(dest, asset.versionCode)
        } catch (e: Exception) {
            dest.delete()

            throw e
        }

        return dest
    }

    private fun requireSameApp(apk: File, expectedCode: Int) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw OtaException("The downloaded file is not an app")

        if (archive.packageName != context.packageName) throw OtaException("The downloaded app has a different identity")

        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode.toInt() else archive.versionCode

        if (code != expectedCode || code <= BuildConfig.VERSION_CODE) throw OtaException("Unexpected app version")

        if (signers(archive) != signers(pm.getPackageInfo(context.packageName, flags))) {
            throw OtaException(tr("Подпись обновления не совпадает с подписью установленного приложения", "The update is signed with a different key than the installed app"))
        }
    }

    private fun signers(info: android.content.pm.PackageInfo): Set<String> {
        val list = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()

            if (s.hasMultipleSigners()) s.apkContentsSigners.toList() else s.signingCertificateHistory.toList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }

        return list.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        params.setAppPackageName(context.packageName)

        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)

        val id = installer.createSession(params)

        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("opencode.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }

            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            session.commit(pending.intentSender)
        }
    }
}

/** Receives the package installer's progress for an in-place update. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return

                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w("update", "could not show the install prompt: ${e.message}")
                    Notifier.update(context, tr("Подтвердите установку обновления в окне системы", "Confirm the update in the system dialog"))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> Log.i("update", "installed")

            else -> {
                Log.w("update", "install failed ($status): $message")
                Notifier.update(context, tr("Не удалось установить обновление: ", "The update could not be installed: ") + message)
            }
        }
    }

    companion object {
        const val ACTION = "dev.opencode.mobile.INSTALL_STATUS"
    }
}
