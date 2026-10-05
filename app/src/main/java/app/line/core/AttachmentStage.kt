package app.line.core

/**
 * Where an attachment is stored on this device, as persisted in the `attachments` table (one row per message).
 * Outgoing: [QUEUED] (blob not on the server yet; the envelope waits in the outbox) -> [UPLOADED] (the envelope may be
 * sent). Incoming: [REMOTE] (not fetched) -> [READY] (verified ciphertext in app storage). [FAILED] and [EXPIRED] go
 * back to the start through a retry. Active transfers are never persisted, so a restart cannot leave a row stuck.
 */
enum class StoredStage(val wire: String) {
    QUEUED("queued"),
    UPLOADED("uploaded"),
    REMOTE("remote"),
    READY("ready"),
    FAILED("failed"),
    EXPIRED("expired");

    fun toTransferStage(transferring: Boolean): TransferStage = when (this) {
        QUEUED, REMOTE -> if (transferring) TransferStage.TRANSFERRING else TransferStage.QUEUED
        UPLOADED, READY -> TransferStage.READY
        FAILED -> TransferStage.FAILED
        EXPIRED -> TransferStage.EXPIRED
    }

    companion object {
        fun parse(wire: String?): StoredStage? = entries.firstOrNull { it.wire == wire }

        fun initial(outgoing: Boolean): StoredStage = if (outgoing) QUEUED else REMOTE

        /** The only moves a row may make; the store applies them as compare-and-set so a stale writer cannot win. */
        fun allowed(outgoing: Boolean, from: StoredStage, to: StoredStage): Boolean = when {
            from == to -> false
            outgoing -> (from == QUEUED && (to == UPLOADED || to == FAILED)) || (from == FAILED && to == QUEUED)
            else -> when (from) {
                REMOTE -> to == READY || to == FAILED || to == EXPIRED
                READY, FAILED, EXPIRED -> to == REMOTE
                else -> false
            }
        }
    }
}

/** One attachment message with everything the pipeline needs; built by the store from the message and `attachments` rows. */
data class StoredAttachment(
    val messageId: String,
    val peer: String,
    val outgoing: Boolean,
    val stage: StoredStage,
    val blobId: String,
    val size: Long,
    val played: Boolean,
    val status: MessageStatus,
    val payload: AttachmentPayload,
) {
    fun view(transferring: Boolean = false, progress: Float = 0f): AttachmentView =
        AttachmentViews.build(messageId, payload, outgoing, stage, played, status, transferring, progress)
}
