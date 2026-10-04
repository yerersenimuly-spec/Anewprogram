package app.line.core

/** Lets at most one progress update through per [intervalMs]; stage changes bypass it. */
class ProgressThrottle(private val intervalMs: Long = 100, private val clock: () -> Long = System::currentTimeMillis) {
    private var last = 0L
    private var started = false

    @Synchronized fun tryAcquire(): Boolean {
        val now = clock()
        if (started && now - last < intervalMs) return false
        started = true
        last = now
        return true
    }

    @Synchronized fun reset() {
        started = false
    }
}
