package app.line.admin

class TripleTapGate(private val windowMillis: Long = 1800) {
    private var firstTap = Long.MIN_VALUE
    private var count = 0
    fun tap(now: Long): Boolean {
        if (count == 0 || now < firstTap || now - firstTap > windowMillis) { firstTap = now; count = 0 }
        count++
        if (count != 3) return false
        reset()
        return true
    }
    fun reset() { count = 0; firstTap = Long.MIN_VALUE }
}
