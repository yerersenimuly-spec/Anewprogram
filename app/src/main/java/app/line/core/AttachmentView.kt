package app.line.core

/** Where an attachment message stands. Outgoing: QUEUED (waiting for a connection) -> TRANSFERRING (upload) -> READY. */
enum class TransferStage { QUEUED, TRANSFERRING, READY, FAILED, EXPIRED }

/**
 * What a chat bubble needs to draw an attachment. [progress] is 0..1 while [stage] is TRANSFERRING.
 * Incoming attachments are fetched on demand (images and voice automatically while the chat is open; files on tap).
 */
data class AttachmentView(
    val messageId: String,
    val payload: AttachmentPayload,
    val stage: TransferStage,
    val progress: Float,
    val outgoing: Boolean,
    val played: Boolean,
)
