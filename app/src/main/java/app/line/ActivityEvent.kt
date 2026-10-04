package app.line

data class ActivityEvent(
    val id: String,
    val kind: String,
    val peer: String,
    val incoming: Boolean,
    val outcome: String,
    val timestamp: Long,
    val durationSeconds: Long = 0,
) {
    companion object {
        const val MESSAGE = "message"
        const val CALL = "call"
    }
}
