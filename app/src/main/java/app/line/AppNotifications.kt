package app.line

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import app.line.ui.Locales

/**
 * Every system notification of the app. Channels are the only place where the Android notification sound and
 * importance are decided; in-app interface sounds are a separate setting ([Settings.uiSounds]).
 */
object AppNotifications {
    const val ACTION_DISMISS_INCOMING = "app.line.action.DISMISS_INCOMING_CALL"
    const val EXTRA_CALL_ID = "line.call_id"
    const val EXTRA_PEER = "peer"
    const val EXTRA_DESTINATION = "destination"
    const val EXTRA_ACCEPT_CALL = "accept_call"
    const val ACTIVE_CALL_ID = 1
    private const val INCOMING_CALL_ID = 2
    private const val MISSED_CALL_ID = 3
    private const val HELD_ID_BASE = 400
    private const val MESSAGE_ID_BASE = 1_000
    private const val CHANNEL_MESSAGES = "messages_v2"
    private const val CHANNEL_CALLS = "calls_v2"
    private const val CHANNEL_MISSED = "missed_v2"
    private const val CHANNEL_ACTIVE = "active_call_v2"
    private const val CHANNEL_BACKGROUND = "background_v2"
    private const val INCOMING_TIMEOUT_MS = 45_000L
    private val legacyChannels = listOf("calls", "call_alerts", "call_history", "messages", "push_messages", "push_calls")

    private fun text(context: Context, id: Int, vararg args: Any): String = Locales.wrap(context).getString(id, *args)
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager(context)
        legacyChannels.forEach { manager.deleteNotificationChannel(it) }
        val ring = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, text(context, R.string.notif_channel_messages),
            NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(true) })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, text(context, R.string.notif_channel_calls),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), ring)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 500, 700, 500)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MISSED, text(context, R.string.notif_channel_missed),
            NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ACTIVE, text(context, R.string.notif_channel_active_call),
            NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_BACKGROUND, text(context, R.string.notif_channel_background),
            NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
    }

    /** One notification per dialog, rebuilt from the unread messages so that it never shows stale or duplicate lines. */
    fun showConversation(context: Context, peer: String, title: String, messages: List<Pair<String, Long>>, hideContent: Boolean) {
        if (messages.isEmpty()) return
        val hidden = text(context, R.string.notif_hidden_message)
        val lines = messages.map { (body, time) -> (if (hideContent) hidden else body) to time }
        val builder = builder(context, CHANNEL_MESSAGES)
            .setContentTitle(title)
            .setContentText(lines.last().first)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_MESSAGES, text(context, R.string.notif_new_message)))
            .setWhen(lines.last().second)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, peer, null, notificationId(peer)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sender = Person.Builder().setName(title).setKey(peer).build()
            val style = Notification.MessagingStyle(Person.Builder().setName(text(context, R.string.notif_you)).build())
            lines.forEach { (body, time) -> style.addMessage(body, time, sender) }
            builder.setStyle(style)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n") { it.first }))
        }
        val reply = RemoteInput.Builder(CallService.KEY_REPLY).setLabel(text(context, R.string.notif_reply)).build()
        val replyIntent = PendingIntent.getService(context, notificationId(peer) + 1,
            Intent(context, CallService::class.java).setAction(CallService.ACTION_REPLY).putExtra(EXTRA_PEER, peer),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        builder.addAction(Notification.Action.Builder(null, text(context, R.string.notif_reply), replyIntent).addRemoteInput(reply)
            .setAllowGeneratedReplies(true).build())
        builder.addAction(Notification.Action.Builder(null, text(context, R.string.notif_mark_read), PendingIntent.getService(
            context, notificationId(peer) + 2,
            Intent(context, CallService::class.java).setAction(CallService.ACTION_MARK_READ).putExtra(EXTRA_PEER, peer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        notifyIfAllowed(context, notificationId(peer), builder.build())
    }

    fun showHeldSender(context: Context, peer: String, name: String) {
        val id = HELD_ID_BASE + (peer.hashCode() and 0xff)
        val body = text(context, R.string.notif_held_text, name)
        val notification = builder(context, CHANNEL_MESSAGES)
            .setContentTitle(text(context, R.string.notif_held_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_MESSAGES, text(context, R.string.notif_new_message)))
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, peer, null, id))
            .build()
        notifyIfAllowed(context, id, notification)
    }

    fun showIncomingCall(context: Context, callId: String, callerName: String?, participants: Int) {
        val title = callerName ?: text(context, R.string.notif_incoming_call)
        val subtitle = text(context, if (participants > 2) R.string.notif_group_call else R.string.notif_incoming_call)
        val decline = PendingIntent.getService(context, callId.hashCode(),
            Intent(context, CallService::class.java).setAction(ACTION_DISMISS_INCOMING).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val answer = openTarget(context, null, "calls", INCOMING_CALL_ID + 10, accept = true)
        val open = openTarget(context, null, "calls", INCOMING_CALL_ID)
        val builder = builder(context, CHANNEL_CALLS)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_CALLS, text(context, R.string.notif_incoming_call)))
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(INCOMING_TIMEOUT_MS)
        if (Build.VERSION.SDK_INT >= 31) {
            val caller = Person.Builder().setName(title).setImportant(true).build()
            builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            builder.addAction(Notification.Action.Builder(null, text(context, R.string.notif_decline), decline).build())
            builder.addAction(Notification.Action.Builder(null, text(context, R.string.notif_answer), answer).build())
        }
        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        notifyIfAllowed(context, INCOMING_CALL_ID, notification)
    }

    fun showMissedCall(context: Context, name: String) {
        val notification = builder(context, CHANNEL_MISSED)
            .setContentTitle(text(context, R.string.notif_missed_call))
            .setContentText(name)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_MISSED, text(context, R.string.notif_missed_call)))
            .setAutoCancel(true)
            .setContentIntent(openTarget(context, null, "calls", MISSED_CALL_ID))
            .build()
        notifyIfAllowed(context, MISSED_CALL_ID, notification)
    }

    fun cancelIncomingCall(context: Context) = manager(context).cancel(INCOMING_CALL_ID)
    fun cancelConversation(context: Context, peer: String) = manager(context).cancel(notificationId(peer))

    fun ongoingCall(context: Context, title: String, participants: Int, muted: Boolean, connected: Boolean): Notification {
        val mute = PendingIntent.getService(context, 1, Intent(context, CallService::class.java).setAction("mute"), PendingIntent.FLAG_IMMUTABLE)
        val hangup = PendingIntent.getService(context, 2, Intent(context, CallService::class.java).setAction("hangup"), PendingIntent.FLAG_IMMUTABLE)
        val status = text(context, when { muted -> R.string.notif_muted; connected -> R.string.notif_in_call; else -> R.string.notif_connecting })
        return builder(context, CHANNEL_ACTIVE)
            .setContentTitle(if (title.isBlank() || participants > 2) text(context, R.string.notif_group_call) else title)
            .setContentText(status)
            .setContentIntent(openTarget(context, null, "calls", ACTIVE_CALL_ID))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CHANNEL_ACTIVE, text(context, R.string.notif_call)))
            .addAction(Notification.Action.Builder(null, text(context, R.string.notif_mic), mute).build())
            .addAction(Notification.Action.Builder(null, text(context, R.string.notif_hangup), hangup).build())
            .build()
    }

    /** The quiet, always-present notification of the persistent connection. */
    fun connection(context: Context, state: CallState): Notification {
        val status = text(context, when (state.link) {
            Link.ONLINE -> R.string.notif_bg_online
            Link.WAITING_NETWORK -> R.string.notif_bg_waiting_network
            else -> R.string.notif_bg_connecting
        })
        val detail = if (state.pending > 0) "$status · ${text(context, R.string.notif_bg_unsent, state.pending)}" else status
        return builder(context, CHANNEL_BACKGROUND)
            .setContentTitle(text(context, R.string.app_name))
            .setContentText(detail)
            .setContentIntent(openTarget(context, null, null, ACTIVE_CALL_ID))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun builder(context: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_notification)

    private fun publicVersion(context: Context, channel: String, title: String): Notification =
        builder(context, channel).setContentTitle(text(context, R.string.app_name)).setContentText(title).build()

    private fun openTarget(context: Context, peer: String?, destination: String?, request: Int, accept: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (peer != null) intent.putExtra(EXTRA_PEER, peer)
        if (destination != null) intent.putExtra(EXTRA_DESTINATION, destination)
        if (accept) intent.putExtra(EXTRA_ACCEPT_CALL, true)
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notificationId(peer: String): Int = MESSAGE_ID_BASE + (peer.hashCode() and 0x0fffffff)

    private fun notifyIfAllowed(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { manager(context).notify(id, notification) }
    }
}
