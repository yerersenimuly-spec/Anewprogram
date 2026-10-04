package app.line.core

/** Reconnect delays: a fast first retry, then exponential growth to a ceiling, with jitter to avoid herds. */
class ReconnectPolicy(
    private val delaysMs: LongArray = longArrayOf(300, 1_000, 2_000, 4_000, 8_000, 15_000, 30_000),
    private val random: () -> Double = Math::random,
) {
    private var attempt = 0

    fun reset() { attempt = 0 }

    fun nextDelayMs(): Long {
        val base = delaysMs[attempt.coerceAtMost(delaysMs.lastIndex)]
        attempt++
        if (base < 1_000) return base
        return (base * (0.8 + 0.4 * random())).toLong()
    }
}
