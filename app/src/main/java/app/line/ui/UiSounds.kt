package app.line.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import app.line.R

/** In-app feedback cues. Notification sounds stay with the Android notification channels. */
enum class UiCue(internal val resId: Int, internal val gain: Float) {
    TAP(R.raw.ui_tap, 0.35f),
    SEND(R.raw.ui_send, 0.8f),
    RECEIVE(R.raw.ui_receive, 0.8f),
    ERROR(R.raw.ui_error, 0.8f),
    TOGGLE_ON(R.raw.ui_toggle_on, 0.45f),
    TOGGLE_OFF(R.raw.ui_toggle_off, 0.45f),
    RECORD_START(R.raw.ui_record_start, 0.45f),
    RECORD_CANCEL(R.raw.ui_record_cancel, 0.45f),
    CALL_CONNECTED(R.raw.ui_call_connected, 0.8f),
    CALL_ENDED(R.raw.ui_call_ended, 0.8f);

    internal val isCallCue: Boolean get() = this == CALL_CONNECTED || this == CALL_ENDED
}

internal object UiSoundPolicy {
    /**
     * Plays only when the user enabled UI sounds, the ringer is audible and no call audio is active.
     * Call cues pass [communicationAllowed] because they fire while the app's own VoIP session
     * holds MODE_IN_COMMUNICATION; cellular calls and ringing still silence them.
     */
    fun shouldPlay(enabled: Boolean, ringerMode: Int, audioMode: Int, communicationAllowed: Boolean = false): Boolean {
        if (!enabled || ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        return when (audioMode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> false
            AudioManager.MODE_IN_COMMUNICATION -> communicationAllowed
            else -> true
        }
    }
}

object UiSounds {
    const val KEY = "ui_sounds"
    private const val PREFS = "line-ui"
    private const val MAX_STREAMS = 3
    private const val THROTTLE_MS = 45L

    private class Bank(val pool: SoundPool) {
        val sampleIds = IntArray(UiCue.entries.size)
        val ready = BooleanArray(UiCue.entries.size)
        val lastPlayedAt = LongArray(UiCue.entries.size) { -THROTTLE_MS }
    }

    private val lock = Any()
    private var bank: Bank? = null

    /** Fire-and-forget; never throws and never blocks on sample loading. */
    fun play(context: Context, cue: UiCue) {
        runCatching { playUnchecked(context.applicationContext ?: context, cue) }
    }

    /** Frees the native pool; the next [play] recreates it lazily. */
    fun release() {
        runCatching {
            synchronized(lock) {
                bank?.pool?.release()
                bank = null
            }
        }
    }

    private fun playUnchecked(app: Context, cue: UiCue) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
        if (!UiSoundPolicy.shouldPlay(enabled, audio.ringerMode, audio.mode, cue.isCallCue)) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val current = bank ?: createBank(app).also { bank = it }
            val i = cue.ordinal
            // Samples load asynchronously: a cue requested before its load completes is dropped, not queued.
            if (!current.ready[i] || now - current.lastPlayedAt[i] < THROTTLE_MS) return
            current.lastPlayedAt[i] = now
            current.pool.play(current.sampleIds[i], cue.gain, cue.gain, 1, 0, 1f)
        }
    }

    private fun createBank(app: Context): Bank {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(attributes).build()
        val created = Bank(pool)
        pool.setOnLoadCompleteListener { loaded, sampleId, status -> onLoaded(loaded, sampleId, status) }
        UiCue.entries.forEach { created.sampleIds[it.ordinal] = pool.load(app, it.resId, 1) }
        return created
    }

    private fun onLoaded(pool: SoundPool, sampleId: Int, status: Int) {
        synchronized(lock) {
            val current = bank?.takeIf { it.pool === pool } ?: return
            val i = current.sampleIds.indexOf(sampleId)
            if (status == 0 && sampleId > 0 && i >= 0) current.ready[i] = true
        }
    }
}
