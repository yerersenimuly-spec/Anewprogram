package app.line.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.line.AppNotifications
import app.line.MainActivity
import app.line.R

object PushAlerts {
    private const val MESSAGE_CHANNEL = "push_messages"
    private const val CALL_CHANNEL = "push_calls"
    private const val MESSAGE_ID_BASE = 1_200_000_000
    private const val CALL_ID_BASE = 1_350_000_000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(MESSAGE_CHANNEL, label(context, "Сообщения"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = label(context, "Новые сообщения")
            },
            NotificationChannel(CALL_CHANNEL, label(context, "Входящие звонки"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = label(context, "Новые звонки")
            },
        ))
    }

    fun showMessage(context: Context, id: String) {
        if (!enabled(context, "notify_messages")) return
        createChannels(context)
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(context, "Новое сообщение"))
            .setContentText(label(context, "Откройте Line, чтобы прочитать сообщение"))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePreview(context))
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, "notifications", messageNotificationId(id)))
            .build()
        notifyIfAllowed(context, messageNotificationId(id), notification)
    }

    fun showCall(context: Context, id: String) {
        if (!enabled(context, "notify_calls")) return
        createChannels(context)
        val notification = Notification.Builder(context, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(context, "Входящий звонок"))
            .setContentText(label(context, "Откройте Line, чтобы ответить"))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePreview(context))
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, "calls", callNotificationId(id)))
            .build()
        notifyIfAllowed(context, callNotificationId(id), notification)
    }

    fun dismissMessage(context: Context, id: String) = cancel(context, messageNotificationId(id))

    fun dismissCall(context: Context, id: String) = cancel(context, callNotificationId(id))

    private fun enabled(context: Context, key: String) = context.getSharedPreferences("line-ui", Context.MODE_PRIVATE)
        .getBoolean(key, true)

    private fun openTarget(context: Context, destination: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AppNotifications.EXTRA_DESTINATION, destination)
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun privatePreview(context: Context) = Notification.Builder(context, MESSAGE_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(label(context, "Line"))
        .setContentText(label(context, "Разблокируйте устройство, чтобы увидеть подробности"))
        .build()

    private fun notifyIfAllowed(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    private fun messageNotificationId(id: String) = MESSAGE_ID_BASE + (id.hashCode() and 0x07ffffff)

    private fun callNotificationId(id: String) = CALL_ID_BASE + (id.hashCode() and 0x07ffffff)

    private fun label(context: Context, russian: String): String {
        val language = context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).getString("language", "ru")
        return when (language) {
            "en" -> when (russian) {
                "Сообщения" -> "Messages"
                "Новые сообщения" -> "New messages"
                "Входящие звонки" -> "Incoming calls"
                "Новые звонки" -> "New calls"
                "Новое сообщение" -> "New message"
                "Откройте Line, чтобы прочитать сообщение" -> "Open Line to read your message"
                "Входящий звонок" -> "Incoming call"
                "Откройте Line, чтобы ответить" -> "Open Line to answer"
                "Разблокируйте устройство, чтобы увидеть подробности" -> "Unlock your device to see details"
                else -> "Line"
            }
            "kk" -> when (russian) {
                "Сообщения" -> "Хабарламалар"
                "Новые сообщения" -> "Жаңа хабарламалар"
                "Входящие звонки" -> "Кіріс қоңыраулар"
                "Новые звонки" -> "Жаңа қоңыраулар"
                "Новое сообщение" -> "Жаңа хабарлама"
                "Откройте Line, чтобы прочитать сообщение" -> "Хабарламаны оқу үшін Line ашыңыз"
                "Входящий звонок" -> "Кіріс қоңырау"
                "Откройте Line, чтобы ответить" -> "Жауап беру үшін Line ашыңыз"
                "Разблокируйте устройство, чтобы увидеть подробности" -> "Толық мәліметті көру үшін құрылғы құлпын ашыңыз"
                else -> "Line"
            }
            else -> russian
        }
    }
}
