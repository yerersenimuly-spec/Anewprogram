package app.line.ui.chat

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bubble box for a photo: keeps the aspect ratio inside the limits and never collapses to a sliver. */
object ImageSizing {
    data class Box(val width: Int, val height: Int)

    private const val MIN_ASPECT = 0.5f
    private const val MAX_ASPECT = 2f

    fun box(width: Int?, height: Int?, maxWidth: Int, maxHeight: Int, minEdge: Int): Box {
        if (width == null || height == null || width <= 0 || height <= 0) return Box(maxWidth, (maxWidth * 0.75f).roundToInt())
        val aspect = (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
        var w: Float
        var h: Float
        if (aspect >= 1f) {
            w = maxWidth.toFloat(); h = w / aspect
        } else {
            h = maxHeight.toFloat(); w = h * aspect
        }
        if (h > maxHeight) { h = maxHeight.toFloat(); w = h * aspect }
        if (w > maxWidth) { w = maxWidth.toFloat(); h = w / aspect }
        return Box(max(w.roundToInt(), min(minEdge, maxWidth)), max(h.roundToInt(), min(minEdge, maxHeight)))
    }
}

/** Voice message arithmetic: durations, waveform bars, playback speed. */
object VoiceMath {
    val SPEEDS = floatArrayOf(1f, 1.5f, 2f)

    /** "0:07", "12:30", "1:02:03". */
    fun duration(millis: Long, roundUp: Boolean = false): String {
        val clamped = max(millis, 0L)
        val total = if (roundUp) ceil(clamped / 1000.0).toLong() else clamped / 1000
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** Resamples a stored waveform (0..100 per byte) to [count] bars, keeping peaks; a missing waveform is flat. */
    fun bars(waveform: ByteArray?, count: Int, floor: Int = 6): IntArray {
        val result = IntArray(max(count, 0)) { floor }
        if (waveform == null || waveform.isEmpty() || count <= 0) return result
        for (bar in 0 until count) {
            val from = (bar.toLong() * waveform.size / count).toInt()
            val to = max(from + 1, ((bar + 1L) * waveform.size / count).toInt())
            var peak = 0
            for (index in from until min(to, waveform.size)) peak = max(peak, waveform[index].toInt())
            result[bar] = peak.coerceIn(floor, 100)
        }
        return result
    }

    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        return SPEEDS[(index + 1) % SPEEDS.size]
    }

    /** Number of bars drawn as played for a progress in 0..1. */
    fun playedBars(progress: Float, count: Int): Int = (progress.coerceIn(0f, 1f) * count).roundToInt()

    fun fraction(x: Float, left: Float, width: Float): Float = if (width <= 0f) 0f else ((x - left) / width).coerceIn(0f, 1f)
}

/** A file size as a value and a unit; the UI picks the localized unit label. */
object SizeFormat {
    enum class Unit { B, KB, MB }

    data class Size(val unit: Unit, val value: Double, val fractionDigits: Int)

    fun of(bytes: Long): Size {
        var value = max(bytes, 0L).toDouble()
        var unit = Unit.B
        while (value >= 1024 && unit != Unit.MB) {
            value /= 1024
            unit = Unit.entries[unit.ordinal + 1]
        }
        val digits = if (unit == Unit.B) 0 else if (value < 10) 1 else 0
        return Size(unit, value, digits)
    }
}

/** Icon for a file bubble by MIME type. */
object FileKinds {
    fun icon(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("audio/") -> "waveform"
        else -> "file"
    }
}
