package app.line.core

/** How the client reacts to a server `error` that names a message id. */
enum class SendFailure {
    /** Keep the message pending and retry later. */
    RETRY,
    /** The message can never be delivered; show it as failed. */
    PERMANENT;

    companion object {
        private val retryable = setOf("storage_unavailable", "rate_limited", "mailbox_full", "chat_disabled", "capacity")
        fun classify(code: String): SendFailure = if (code in retryable) RETRY else PERMANENT
    }
}
