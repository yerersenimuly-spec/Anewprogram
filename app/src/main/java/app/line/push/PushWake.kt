package app.line.push

import android.content.Context
import app.line.AppNotifications
import app.line.Settings
import app.line.core.PushPayload

/** Turns a content-free push hint into work: connect, fetch the encrypted inbox, notify from decrypted data. */
object PushWake {
    fun handle(context: Context, payload: PushPayload) {
        when (payload.kind) {
            PushPayload.Kind.MESSAGE -> sync(context)
            PushPayload.Kind.CALL -> {
                if (Settings.notifyCalls(context)) AppNotifications.showIncomingCall(context, payload.id, null, 0)
                sync(context)
            }
            PushPayload.Kind.CALL_ENDED -> {
                AppNotifications.cancelIncomingCall(context)
                context.getSharedPreferences("line", Context.MODE_PRIVATE).edit()
                    .putString("pending_call_dismissal", payload.id)
                    .putLong("pending_call_dismissal_at", System.currentTimeMillis()).apply()
                sync(context)
            }
        }
    }

    fun sync(context: Context) = PushInboxWorker.enqueue(context)
}
