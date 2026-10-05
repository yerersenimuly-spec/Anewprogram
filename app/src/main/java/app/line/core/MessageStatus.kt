package app.line.core

/**
 * Delivery state of one chat message. Outgoing messages move forward only
 * (PENDING -> SENT -> DELIVERED -> READ); FAILED may be retried. RECEIVED is an unread incoming message.
 */
enum class MessageStatus(val wire: String) {
    PENDING("pending"),
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    FAILED("failed"),
    RECEIVED("received");

    companion object {
        /** Legacy 0.7 rows used "queued" both for "waiting in the local outbox" and "accepted by the server". */
        fun parse(value: String?): MessageStatus = when (value) {
            "pending" -> PENDING
            "queued", "sent" -> SENT
            "delivered" -> DELIVERED
            "read" -> READ
            "failed" -> FAILED
            "received" -> RECEIVED
            else -> PENDING
        }

        private fun rank(status: MessageStatus): Int = when (status) {
            PENDING -> 0
            SENT -> 1
            DELIVERED -> 2
            READ -> 3
            FAILED, RECEIVED -> -1
        }

        /** Result of applying [next] on top of [current] for an outgoing message; never moves backwards. */
        fun advance(current: MessageStatus, next: MessageStatus): MessageStatus = when {
            next == current -> current
            next == FAILED -> if (current == PENDING) FAILED else current
            current == FAILED -> if (next == RECEIVED) current else next
            current == RECEIVED || next == RECEIVED -> current
            rank(next) > rank(current) -> next
            else -> current
        }
    }
}
