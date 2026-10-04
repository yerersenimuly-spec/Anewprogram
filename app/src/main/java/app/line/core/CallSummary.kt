package app.line.core

/** What the post-call screen shows. Built once when a call attempt ends. */
data class CallSummary(
    val id: String,
    val peers: List<String>,
    val incoming: Boolean,
    val outcome: String,
    val durationSeconds: Long,
    val startedAt: Long,
    val endedAt: Long,
) {
    val group: Boolean get() = peers.size > 1
    val connected: Boolean get() = durationSeconds > 0 || outcome == "completed"

    companion object {
        /**
         * A summary is shown for a conversation that took place, a missed call, a failure, or an outgoing call the other side
         * declined. Cancelling an outgoing call or declining an incoming one yourself needs no recap.
         */
        fun worthShowing(outcome: String, durationSeconds: Long, incoming: Boolean): Boolean = when {
            durationSeconds > 0 -> true
            outcome == "missed" || outcome == "failed" -> true
            outcome == "declined" -> !incoming
            else -> false
        }

        fun format(seconds: Long): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
