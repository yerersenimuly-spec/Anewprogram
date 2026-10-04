package app.line.ui.chat

import android.content.Context
import app.line.media.attachments.VoicePlayer
import app.line.media.attachments.VoiceRecorderException
import java.io.File

/** Owns the one voice player of a conversation and tells the screen which bubble changed. */
class VoiceController(context: Context, private val onChange: (String) -> Unit, private val onError: (VoiceRecorderException) -> Unit) {
    private val player = VoicePlayer(context)
    var activeId: String? = null
        private set
    private var state = VoicePlayer.State.IDLE
    private var position = 0
    private var duration = 0

    init {
        player.onProgress = { pos, dur -> position = pos; duration = dur; activeId?.let(onChange) }
        player.onStateChanged = { next ->
            state = next
            if (next == VoicePlayer.State.IDLE) clear() else activeId?.let(onChange)
        }
        player.onCompleted = { clear() }
        player.onError = { error -> clear(); onError(error) }
    }

    private fun clear() {
        val previous = activeId
        activeId = null
        position = 0; duration = 0
        previous?.let(onChange)
    }

    fun playback(id: String): Playback? =
        if (id != activeId) null
        else Playback(state == VoicePlayer.State.PLAYING, state == VoicePlayer.State.PREPARING, position, duration, player.speed)

    fun isActive(id: String) = id == activeId

    /** Pauses or resumes the message that is currently loaded. */
    fun pauseOrResume() { if (player.isPlaying) player.pause() else player.play() }

    fun start(id: String, file: File, startMs: Int) {
        val previous = activeId
        activeId = id
        position = startMs; duration = 0
        previous?.let(onChange)
        player.play(file, startMs)
        onChange(id)
    }

    /** Seeks inside the loaded message. */
    fun seek(fraction: Float) { if (duration > 0) player.seekTo((fraction * duration).toInt()) }

    fun cycleSpeed() { player.setSpeed(VoiceMath.nextSpeed(player.speed)); activeId?.let(onChange) }

    fun stop() { VoicePlayer.stopAll(); player.release(); clear() }
}
