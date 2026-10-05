package app.line.media.attachments

/**
 * Fixed-size store for amplitude samples. When it fills up, neighbouring pairs are merged (keeping the louder) and
 * every later value covers twice as many raw samples, so any recording length fits and the whole of it stays visible.
 */
internal class AmplitudeBuffer(capacity: Int) {
    private val values = IntArray(capacity - capacity % 2)
    private var count = 0
    private var stride = 1
    private var pending = 0
    private var pendingCount = 0

    init {
        require(values.size >= 2) { "capacity must be at least 2" }
    }

    fun add(sample: Int) {
        pending = maxOf(pending, sample)
        if (++pendingCount < stride) return
        values[count++] = pending
        pending = 0
        pendingCount = 0
        if (count == values.size) merge()
    }

    /** Snapshot including the partially filled last value. */
    fun toIntArray(): IntArray = if (pendingCount > 0) values.copyOf(count + 1).also { it[count] = pending } else values.copyOf(count)

    fun clear() {
        count = 0
        stride = 1
        pending = 0
        pendingCount = 0
    }

    private fun merge() {
        for (index in 0 until count / 2) values[index] = maxOf(values[2 * index], values[2 * index + 1])
        count /= 2
        stride *= 2
    }
}
