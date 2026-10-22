package dev.opencode.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_SERVER = "server"
    const val CH_ACTIVITY = "activity"
    const val CH_UPDATES = "updates"

    const val ID_SERVER = 1
    const val ID_UPDATE = 2
    const val ID_ACTIVITY = 100

    fun init(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(CH_SERVER, tr("Сервер", "Server"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Постоянное уведомление, пока сервер работает в фоне", "Shown while the server keeps running in the background")
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_ACTIVITY, tr("Задачи", "Tasks"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr("Завершение задач и запросы разрешений", "Finished tasks and permission requests")
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_UPDATES, tr("Обновления", "Updates"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Доступные и установленные обновления", "Available and installed updates")
                setShowBadge(false)
            },
        )
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun server(context: Context, title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(context, CH_SERVER)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_opencode), tr("Остановить", "Stop"), stop).build())
            .build()
    }

    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!Permissions.notificationsGranted(context)) return

        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(context))
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    /** Offers a link the app could not open itself because it was not on screen. Tapping opens it in the app. */
    fun openLink(context: Context, url: String, preview: Boolean) {
        if (!Permissions.notificationsGranted(context)) return

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(if (preview) MainActivity.EXTRA_PREVIEW else MainActivity.EXTRA_EXTERNAL, url)
        val pending = PendingIntent.getActivity(context, url.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (preview) tr("Сайт готов к просмотру", "Site is ready to preview") else tr("Откройте ссылку", "Open the link")
        val notification = Notification.Builder(context, CH_ACTIVITY)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(title)
            .setContentText(Urls.displayHost(url))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(ID_ACTIVITY + 0x1000 + (url.hashCode() and 0xFFF), notification)
    }

    fun update(context: Context, text: String) = post(context, CH_UPDATES, ID_UPDATE, tr("Обновление OpenCode", "OpenCode update"), text)

    fun activity(context: Context, key: String, title: String, text: String) =
        post(context, CH_ACTIVITY, ID_ACTIVITY + (key.hashCode() and 0xFFF), title, text)
}
