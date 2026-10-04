package app.line.media.attachments

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Plays one voice message from a decrypted temp file (decrypt with `AttachmentCrypto.openStream` into the cache
 * first). Use it from the main thread only. Only one player makes sound at a time: starting playback stops every
 * other [VoicePlayer] (their [onStateChanged] reports [State.IDLE]); [stopAll] stops whichever is playing. Audio focus
 * is requested as transient-may-duck while playing. [MediaPlayer] needs a device, so this class cannot be unit-tested
 * on the JVM.
 */
class VoicePlayer(context: Context) {
    enum class State { IDLE, PREPARING, PAUSED, PLAYING, COMPLETED }

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var playWhenPrepared = false
    private var pendingSeekMs = 0

    var state: State = State.IDLE
        private set

    /** Playback speed in effect: [MIN_SPEED]..[MAX_SPEED], 1 by default; kept across files. */
    var speed: Float = 1f
        private set

    /** Called about every [PROGRESS_INTERVAL_MS] ms while playing, and after a seek or pause. */
    var onProgress: ((positionMs: Int, durationMs: Int) -> Unit)? = null

    var onCompleted: (() -> Unit)? = null

    /** Called whenever [state] changes, including when another player takes over. */
    var onStateChanged: ((State) -> Unit)? = null

    var onError: ((VoiceRecorderException) -> Unit)? = null

    val isPlaying: Boolean get() = state == State.PLAYING

    val durationMs: Int
        get() = if (state == State.IDLE || state == State.PREPARING) 0 else read { it.duration } ?: 0

    val positionMs: Int
        get() = if (state == State.IDLE || state == State.PREPARING) pendingSeekMs else read { it.currentPosition } ?: 0

    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.PLAYING) return
            notifyProgress(positionMs)
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(DUCKED_VOLUME, DUCKED_VOLUME)
            AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
        }
    }

    /** Loads [file] and starts playing at [startMs]; whatever this player was playing is dropped. */
    fun play(file: File, startMs: Int = 0) {
        claim()
        discardPlayer()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(attributes)
            created.setDataSource(file.absolutePath)
            created.setOnPreparedListener { onPrepared(it) }
            created.setOnCompletionListener { onCompletion(it) }
            created.setOnErrorListener { failed, _, _ -> onPlayerError(failed) }
            created.prepareAsync()
        } catch (e: Exception) {
            created.release()
            release()
            throw VoiceRecorderException(VoiceRecorderException.Reason.PLAYBACK, "Cannot play the voice message", e)
        }
        player = created
        playWhenPrepared = true
        pendingSeekMs = maxOf(0, startMs)
        setState(State.PREPARING)
    }

    /** Resumes the loaded message; from the start when it had finished. */
    fun play() {
        when (state) {
            State.PAUSED, State.COMPLETED -> {
                claim()
                start()
            }
            State.PREPARING -> playWhenPrepared = true
            State.IDLE, State.PLAYING -> Unit
        }
    }

    fun pause() {
        when (state) {
            State.PREPARING -> playWhenPrepared = false
            State.PLAYING -> {
                try {
                    player?.pause()
                } catch (e: IllegalStateException) {
                    return fail(e)
                }
                handler.removeCallbacks(ticker)
                abandonFocus()
                setState(State.PAUSED)
                notifyProgress(positionMs)
            }
            else -> Unit
        }
    }

    fun seekTo(positionMs: Int) {
        val limit = durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE
        val target = positionMs.coerceIn(0, limit)
        when (state) {
            State.IDLE -> Unit
            State.PREPARING -> pendingSeekMs = target
            else -> {
                try {
                    player?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
                } catch (e: IllegalStateException) {
                    return fail(e)
                }
                if (state == State.COMPLETED) setState(State.PAUSED)
                notifyProgress(target)
            }
        }
    }

    /** Sets the playback speed (clamped to [MIN_SPEED]..[MAX_SPEED]); the UI offers 1, 1.5 and 2. */
    fun setSpeed(value: Float) {
        speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        // Applying parameters to a paused MediaPlayer starts it on some API levels, so only a playing one is touched.
        if (state == State.PLAYING) player?.let(::applySpeed)
    }

    /** Stops playback and frees the decoder; the player can be reused with [play]. */
    fun release() {
        discardPlayer()
        if (current === this) current = null
    }

    private fun start() {
        val active = player ?: return
        requestFocus()
        try {
            active.start()
        } catch (e: IllegalStateException) {
            return fail(e)
        }
        applySpeed(active)
        setState(State.PLAYING)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun onPrepared(prepared: MediaPlayer) {
        if (prepared !== player) return
        setState(State.PAUSED)
        if (pendingSeekMs > 0) {
            try {
                prepared.seekTo(minOf(pendingSeekMs, prepared.duration).toLong(), MediaPlayer.SEEK_CLOSEST)
            } catch (e: IllegalStateException) {
                return fail(e)
            }
            notifyProgress(pendingSeekMs)
        }
        pendingSeekMs = 0
        if (playWhenPrepared) start()
    }

    private fun onCompletion(finished: MediaPlayer) {
        if (finished !== player) return
        handler.removeCallbacks(ticker)
        abandonFocus()
        setState(State.COMPLETED)
        notifyProgress(durationMs)
        onCompleted?.invoke()
    }

    private fun onPlayerError(failed: MediaPlayer): Boolean {
        if (failed === player) fail(null)
        return true
    }

    private fun fail(cause: Exception?) {
        release()
        onError?.invoke(VoiceRecorderException(VoiceRecorderException.Reason.PLAYBACK, "Playback failed", cause))
    }

    private fun claim() {
        val other = current
        if (other != null && other !== this) other.release()
        current = this
    }

    private fun discardPlayer() {
        handler.removeCallbacks(ticker)
        abandonFocus()
        player?.let {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try {
                it.reset()
            } catch (e: RuntimeException) {
                // Releasing below is what matters.
            }
            it.release()
        }
        player = null
        playWhenPrepared = false
        pendingSeekMs = 0
        setState(State.IDLE)
    }

    private fun setState(next: State) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next)
    }

    private fun notifyProgress(position: Int) {
        onProgress?.invoke(position, durationMs)
    }

    private fun applySpeed(active: MediaPlayer) {
        try {
            active.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        } catch (e: RuntimeException) {
            // The device cannot change the speed; playback continues at normal speed.
        }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private inline fun <T> read(action: (MediaPlayer) -> T): T? = try {
        player?.let(action)
    } catch (e: IllegalStateException) {
        null
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
        const val PROGRESS_INTERVAL_MS = 50L
        private const val DUCKED_VOLUME = 0.3f
        private var current: VoicePlayer? = null

        /** Stops whichever player is active. */
        fun stopAll() {
            val active = current
            current = null
            active?.release()
        }
    }
}
