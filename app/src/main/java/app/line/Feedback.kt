package app.line

import android.content.Context
import android.media.AudioManager

object Feedback {
    fun messageSent(context: Context) {
        if (!context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).getBoolean("message_sound", true)) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.12f)
        }
    }

    fun interfaceClick(context: Context) {
        if (!context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).getBoolean("interface_sound", false)) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.1f)
        }
    }
}
