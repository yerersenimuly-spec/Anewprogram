package app.line

import android.content.Context

/**
 * User preferences. Notification settings and interface sounds are deliberately independent switches:
 * notifications are delivered by Android channels, interface sounds only play inside the running app.
 */
object Settings {
    const val NOTIFY_MESSAGES = "notify_messages"
    const val NOTIFY_CALLS = "notify_calls"
    const val NOTIFY_PREVIEW = "notify_preview"
    const val UI_SOUNDS = "ui_sounds"
    const val READ_RECEIPTS = "read_receipts"
    const val PERSISTENT = "persistent"

    private fun ui(context: Context) = context.getSharedPreferences("line-ui", Context.MODE_PRIVATE)
    private fun core(context: Context) = context.getSharedPreferences("line", Context.MODE_PRIVATE)

    fun notifyMessages(context: Context) = ui(context).getBoolean(NOTIFY_MESSAGES, true)
    fun notifyCalls(context: Context) = ui(context).getBoolean(NOTIFY_CALLS, true)
    fun notifyPreview(context: Context) = ui(context).getBoolean(NOTIFY_PREVIEW, true)
    fun uiSounds(context: Context) = ui(context).getBoolean(UI_SOUNDS, true)
    fun readReceipts(context: Context) = ui(context).getBoolean(READ_RECEIPTS, true)
    fun persistent(context: Context) = core(context).getBoolean(PERSISTENT, true)

    fun set(context: Context, key: String, value: Boolean) {
        (if (key == PERSISTENT) core(context) else ui(context)).edit().putBoolean(key, value).apply()
    }
}
