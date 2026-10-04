package app.line.media.attachments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Records a voice message as AAC-LC in an MPEG-4 container (mono, 32 kbps, 32 kHz) with [MediaRecorder].
 *
 * Use it from the main thread only. Starting stops any [VoicePlayer]. A recording shorter than [MIN_DURATION_MS] is
 * discarded ([stop] returns null) and one that reaches [MAX_DURATION_MS] ends by itself through the `onAutoStop`
 * callback. [stop] and [cancel] are safe to call at any time and twice. The amplitude is polled every
 * [POLL_INTERVAL_MS] ms into a bounded buffer for the waveform. [MediaRecorder] needs a device and the microphone, so
 * this class cannot be unit-tested on the JVM.
 */
class VoiceRecorder(context: Context) {
    /** A finished recording: [amplitudes] are `getMaxAmplitude` samples (0..32767) for `Waveform.fromAmplitudes`. */
    class Result(val durationMs: Long, val file: File, val amplitudes: IntArray)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val samples = AmplitudeBuffer(SAMPLE_CAPACITY)
    private val level = MutableStateFlow(0)
    private val elapsed = MutableStateFlow(0L)
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L
    private var onAutoStop: ((Result?) -> Unit)? = null
    private var onError: ((VoiceRecorderException) -> Unit)? = null

    /** Peak amplitude (0..32767) over the last poll interval, for a live level meter; 0 when idle. */
    val amplitude: StateFlow<Int> = level.asStateFlow()

    /** Milliseconds recorded so far; 0 when idle. */
    val elapsedMs: StateFlow<Long> = elapsed.asStateFlow()

    val isRecording: Boolean get() = recorder != null

    private val poll = object : Runnable {
        override fun run() {
            val active = recorder ?: return
            val peak = try {
                active.maxAmplitude
            } catch (e: RuntimeException) {
                0
            }
            samples.add(peak)
            level.value = peak
            elapsed.value = minOf(SystemClock.elapsedRealtime() - startedAt, MAX_DURATION_MS)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Starts recording into [outputFile]. [onAutoStop] receives the finished recording when the maximum duration ends
     * it; [onError] is called when the recorder fails while running (the recording is discarded).
     * Throws [VoiceRecorderException] when it cannot start.
     */
    fun start(
        outputFile: File,
        onAutoStop: (Result?) -> Unit = {},
        onError: (VoiceRecorderException) -> Unit = {},
    ) {
        if (recorder != null) throw VoiceRecorderException(VoiceRecorderException.Reason.STATE, "Already recording")
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw VoiceRecorderException(VoiceRecorderException.Reason.PERMISSION, "RECORD_AUDIO is not granted")
        }
        VoicePlayer.stopAll()
        val created = newRecorder()
        try {
            outputFile.parentFile?.mkdirs()
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(SAMPLE_RATE)
            created.setAudioEncodingBitRate(BIT_RATE)
            created.setMaxDuration(MAX_DURATION_MS.toInt())
            created.setOutputFile(outputFile)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishAutomatically()
            }
            created.setOnErrorListener { _, _, _ -> failWhileRunning() }
            created.prepare()
            created.start()
        } catch (e: Exception) {
            discard(created)
            outputFile.delete()
            throw when (e) {
                is SecurityException -> VoiceRecorderException(VoiceRecorderException.Reason.PERMISSION, "Microphone access was refused", e)
                is IOException -> VoiceRecorderException(VoiceRecorderException.Reason.STORAGE, "Cannot write the recording", e)
                else -> VoiceRecorderException(VoiceRecorderException.Reason.BUSY, "The microphone is not available", e)
            }
        }
        recorder = created
        target = outputFile
        this.onAutoStop = onAutoStop
        this.onError = onError
        startedAt = SystemClock.elapsedRealtime()
        samples.clear()
        level.value = 0
        elapsed.value = 0
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /**
     * Ends the recording. Returns null when nothing is being recorded or the recording is shorter than
     * [MIN_DURATION_MS] (the file is deleted then). Throws [VoiceRecorderException] when the file could not be finalised.
     */
    fun stop(): Result? {
        val active = recorder ?: return null
        val file = target ?: return null
        val duration = minOf(SystemClock.elapsedRealtime() - startedAt, MAX_DURATION_MS)
        val amplitudes = samples.toIntArray()
        var failure: RuntimeException? = null
        try {
            active.stop()
        } catch (e: RuntimeException) {
            failure = e
        }
        release(active)
        if (duration < MIN_DURATION_MS) {
            file.delete()
            return null
        }
        if (failure != null || !file.exists() || file.length() == 0L) {
            file.delete()
            throw VoiceRecorderException(VoiceRecorderException.Reason.FAILED, "The recording could not be finalised", failure)
        }
        return Result(duration, file, amplitudes)
    }

    /** Abandons the recording and deletes the file. */
    fun cancel() {
        val active = recorder ?: return
        val file = target
        release(active)
        file?.delete()
    }

    private fun finishAutomatically() {
        val callback = onAutoStop
        val error = onError
        val result = try {
            stop()
        } catch (e: VoiceRecorderException) {
            error?.invoke(e)
            return
        }
        callback?.invoke(result)
    }

    private fun failWhileRunning() {
        val error = onError
        cancel()
        error?.invoke(VoiceRecorderException(VoiceRecorderException.Reason.FAILED, "The recorder failed"))
    }

    private fun release(active: MediaRecorder) {
        handler.removeCallbacks(poll)
        discard(active)
        recorder = null
        target = null
        onAutoStop = null
        onError = null
        level.value = 0
        elapsed.value = 0
    }

    private fun discard(active: MediaRecorder) {
        active.setOnInfoListener(null)
        active.setOnErrorListener(null)
        try {
            active.reset()
        } catch (e: RuntimeException) {
            // Releasing below is what matters.
        }
        active.release()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()

    companion object {
        const val MIME = "audio/mp4"
        const val FILE_EXTENSION = "m4a"
        const val MIN_DURATION_MS = 700L
        const val MAX_DURATION_MS = 10L * 60 * 1000
        const val POLL_INTERVAL_MS = 60L
        private const val SAMPLE_RATE = 32_000
        private const val BIT_RATE = 32_000
        private const val SAMPLE_CAPACITY = 2048
    }
}
