package app.line

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object AppNotifications {
    const val ACTION_DISMISS_INCOMING = "app.line.action.DISMISS_INCOMING_CALL"
    const val EXTRA_CALL_ID = "line.call_id"
    const val EXTRA_PEER = "peer"
    const val EXTRA_DESTINATION = "destination"
    const val ACTIVE_CALL_ID = 1
    private const val INCOMING_CALL_ID = 2
    private const val MESSAGE_ID_BASE = 100
    private const val ACTIVE_CHANNEL = "calls"
    private const val CALL_ALERTS_CHANNEL = "call_alerts"
    private const val CALL_HISTORY_CHANNEL = "call_history"
    private const val MESSAGE_CHANNEL = "messages"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ACTIVE_CHANNEL, label(context, "Активный звонок"), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CALL_ALERTS_CHANNEL, label(context, "Входящие звонки"), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CALL_HISTORY_CHANNEL, label(context, "Пропущенные звонки"), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, label(context, "Сообщения"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun showIncomingMessage(context: Context, peer: String, id: String) {
        val notification = Notification.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(context, "Новое сообщение"))
            .setContentText(label(context, "Приватное сообщение"))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePreview(context))
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, peer, null, messageId(id)))
            .build()
        notifyIfAllowed(context, messageId(id), notification)
    }

    fun showIncomingCall(context: Context, callId: String) {
        val dismiss = PendingIntent.getService(
            context, callId.hashCode(),
            Intent(context, CallService::class.java).setAction(ACTION_DISMISS_INCOMING).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CALL_ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(context, "Входящий звонок"))
            .setContentText(label(context, "Откройте Line, чтобы ответить"))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePreview(context))
            .setContentIntent(openTarget(context, null, "calls", INCOMING_CALL_ID))
            .setDeleteIntent(dismiss)
            .setAutoCancel(false)
            .build()
        notifyIfAllowed(context, INCOMING_CALL_ID, notification)
    }

    fun showMissedCall(context: Context) {
        val notification = Notification.Builder(context, CALL_HISTORY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(context, "Пропущенный звонок"))
            .setContentText(label(context, "Недавние звонки"))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePreview(context))
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, null, "calls", INCOMING_CALL_ID))
            .build()
        notifyIfAllowed(context, INCOMING_CALL_ID, notification)
    }

    fun cancelIncomingCall(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(INCOMING_CALL_ID)
    fun cancelMessage(context: Context, id: String) = context.getSystemService(NotificationManager::class.java).cancel(messageId(id))

    fun ongoingCall(context: Context, muted: Boolean, connected: Boolean): Notification {
        val mute = PendingIntent.getService(context, 1, Intent(context, CallService::class.java).setAction("mute"), PendingIntent.FLAG_IMMUTABLE)
        val hangup = PendingIntent.getService(context, 2, Intent(context, CallService::class.java).setAction("hangup"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, ACTIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label(context, "Групповой звонок"))
            .setContentText(label(context, when { muted -> "Микрофон выключен"; connected -> "В звонке"; else -> "Соединяем…" }))
            .setContentIntent(openTarget(context, null, "calls", ACTIVE_CALL_ID))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePreview(context))
            .addAction(Notification.Action.Builder(null, label(context, "Микрофон"), mute).build())
            .addAction(Notification.Action.Builder(null, label(context, "Завершить"), hangup).build())
            .build()
    }

    private fun privatePreview(context: Context) = Notification.Builder(context, MESSAGE_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(label(context, "Line"))
        .setContentText(label(context, "Приватное сообщение"))
        .build()

    private fun openTarget(context: Context, peer: String?, destination: String?, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (peer != null) intent.putExtra(EXTRA_PEER, peer)
        if (destination != null) intent.putExtra(EXTRA_DESTINATION, destination)
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun messageId(id: String): Int = MESSAGE_ID_BASE + (id.hashCode() and 0x3fffffff)

    private fun notifyIfAllowed(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun label(context: Context, russian: String): String {
        val language = context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).getString("language", "ru")
        val translations = when (language) {
            "en" -> mapOf(
                "Активный звонок" to "Active call", "Входящие звонки" to "Incoming calls", "Пропущенные звонки" to "Missed calls",
                "Сообщения" to "Messages", "Новое сообщение" to "New message", "Приватное сообщение" to "Private message",
                "Входящий звонок" to "Incoming call", "Откройте Line, чтобы ответить" to "Open Line to answer",
                "Пропущенный звонок" to "Missed call", "Недавние звонки" to "Recent calls", "Групповой звонок" to "Group call",
                "Микрофон выключен" to "Microphone muted", "В звонке" to "In call", "Соединяем…" to "Connecting…",
                "Микрофон" to "Microphone", "Завершить" to "Hang up", "Line" to "Line",
            )
            "kk" -> mapOf(
                "Активный звонок" to "Белсенді қоңырау", "Входящие звонки" to "Кіріс қоңыраулар", "Пропущенные звонки" to "Қабылданбаған қоңыраулар",
                "Сообщения" to "Хабарламалар", "Новое сообщение" to "Жаңа хабарлама", "Приватное сообщение" to "Жеке хабарлама",
                "Входящий звонок" to "Кіріс қоңырау", "Откройте Line, чтобы ответить" to "Жауап беру үшін Line ашыңыз",
                "Пропущенный звонок" to "Қабылданбаған қоңырау", "Недавние звонки" to "Соңғы қоңыраулар", "Групповой звонок" to "Топтық қоңырау",
                "Микрофон выключен" to "Микрофон өшірулі", "В звонке" to "Қоңырауда", "Соединяем…" to "Қосылуда…",
                "Микрофон" to "Микрофон", "Завершить" to "Аяқтау", "Line" to "Line",
            )
            else -> emptyMap()
        }
        return translations[russian] ?: russian
    }
}
