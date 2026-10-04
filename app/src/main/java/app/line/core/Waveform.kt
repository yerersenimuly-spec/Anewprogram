package app.line.core

import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Loudness profile of a voice message: one byte (0..100) per bar, built from `MediaRecorder.getMaxAmplitude` samples. */
object Waveform {
    const val DEFAULT_BARS = 48
    const val MAX_BARS = 128
    const val FLOOR = 4
    const val PEAK = 100
    private const val MAX_AMPLITUDE = 32767
    private const val NOISE_GATE = 256
    private const val MIN_REFERENCE = 4096

    /**
     * Scales [samples] (0..32767) to [bars] bars. Loudness is perceptual (square root of the level relative to the
     * loudest bar). Ambient noise below the gate and silence give a flat line at [FLOOR], and a quiet recording is
     * not stretched to full height because the reference peak never drops below [MIN_REFERENCE].
     */
    fun fromAmplitudes(samples: IntArray, bars: Int = DEFAULT_BARS): ByteArray {
        require(bars in 1..MAX_BARS) { "bars must be in 1..$MAX_BARS" }
        val levels = DoubleArray(bars)
        if (samples.size >= bars) {
            for (bar in 0 until bars) {
                val from = (bar.toLong() * samples.size / bars).toInt()
                val to = ((bar + 1L) * samples.size / bars).toInt()
                var squares = 0.0
                for (index in from until to) {
                    val level = clamp(samples[index]).toDouble()
                    squares += level * level
                }
                levels[bar] = sqrt(squares / (to - from))
            }
        } else if (samples.isNotEmpty()) {
            for (bar in 0 until bars) {
                val position = if (bars == 1) 0.0 else bar * (samples.size - 1).toDouble() / (bars - 1)
                val low = position.toInt()
                val high = minOf(low + 1, samples.size - 1)
                val fraction = position - low
                levels[bar] = clamp(samples[low]) * (1 - fraction) + clamp(samples[high]) * fraction
            }
        }
        val gated = DoubleArray(bars) { max(0.0, levels[it] - NOISE_GATE) }
        val reference = max(gated.max(), (MIN_REFERENCE - NOISE_GATE).toDouble())
        return ByteArray(bars) { (FLOOR + (PEAK - FLOOR) * sqrt(gated[it] / reference)).roundToInt().toByte() }
    }

    fun toBase64(waveform: ByteArray): String = Base64.getEncoder().encodeToString(waveform)

    /** Decodes untrusted input: at most [MAX_BARS] bars, each clamped to 0..[PEAK]. Throws [IllegalArgumentException]. */
    fun fromBase64(text: String): ByteArray {
        require(text.length <= (MAX_BARS + 2) / 3 * 4) { "waveform is too long" }
        val bytes = Base64.getDecoder().decode(text)
        require(bytes.size <= MAX_BARS) { "waveform is too long" }
        return ByteArray(bytes.size) { bytes[it].toInt().coerceIn(0, PEAK).toByte() }
    }

    private fun clamp(sample: Int): Int = sample.coerceIn(0, MAX_AMPLITUDE)
}
