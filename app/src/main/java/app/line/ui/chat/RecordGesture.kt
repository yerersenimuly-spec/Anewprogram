package app.line.ui.chat

/**
 * Press-and-hold recording gesture: slide toward the start edge to cancel, slide up to lock the recording.
 * Coordinates are raw pixels; [towardStart] is +1 in LTR and -1 in RTL so "cancel" always points at the field.
 */
class RecordGesture(private val cancelDistance: Float, private val lockDistance: Float, private val towardStart: Int = 1) {
    enum class State { IDLE, HOLDING, CANCEL_ARMED, LOCKED }
    enum class Outcome { SEND, CANCEL, KEEP }

    var state = State.IDLE
        private set

    /** 0..1 how far the finger is on its way to cancelling / locking; drives the hint and the lock pill. */
    var cancelProgress = 0f
        private set
    var lockProgress = 0f
        private set

    private var downX = 0f
    private var downY = 0f

    fun press(x: Float, y: Float) {
        downX = x; downY = y
        state = State.HOLDING
        cancelProgress = 0f; lockProgress = 0f
    }

    /** Returns the state entered by this move, or null when nothing changed. */
    fun move(x: Float, y: Float): State? {
        if (state == State.IDLE || state == State.LOCKED) return null
        val cancelRatio = ((downX - x) * towardStart / cancelDistance).coerceAtLeast(0f)
        val lockRatio = ((downY - y) / lockDistance).coerceAtLeast(0f)
        cancelProgress = cancelRatio.coerceAtMost(1f)
        lockProgress = lockRatio.coerceAtMost(1f)
        val next = when {
            lockRatio >= 1f && lockRatio > cancelRatio -> State.LOCKED
            cancelRatio >= 1f -> State.CANCEL_ARMED
            else -> State.HOLDING
        }
        if (next == state) return null
        state = next
        return next
    }

    /** What lifting the finger means. A locked recording keeps going until the user sends or cancels it explicitly. */
    fun release(): Outcome {
        val outcome = when (state) {
            State.CANCEL_ARMED -> Outcome.CANCEL
            State.LOCKED -> Outcome.KEEP
            State.HOLDING -> Outcome.SEND
            State.IDLE -> Outcome.CANCEL
        }
        if (outcome != Outcome.KEEP) state = State.IDLE
        return outcome
    }

    fun reset() {
        state = State.IDLE
        cancelProgress = 0f; lockProgress = 0f
    }
}
