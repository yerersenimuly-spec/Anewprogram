package app.line

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.line.push.PushRegistrar

/** Restores the background connection after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        PushRegistrar.refresh(context)
        CallService.ensureRunning(context)
    }
}
