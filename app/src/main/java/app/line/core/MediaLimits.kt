package app.line.core

/** Size limits of outgoing attachments; the client stops before the server would. */
object MediaLimits {
    /** Plaintext bytes of one photo (after downscaling), file or voice message. Encrypted it stays under the server's 26 MiB. */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** Ciphertext size of [plainSize] bytes; throws [AttachmentTooLarge] above [MAX_FILE_BYTES]. */
    fun encryptedSizeOrThrow(plainSize: Long): Long {
        if (plainSize > MAX_FILE_BYTES) throw AttachmentTooLarge(plainSize, MAX_FILE_BYTES)
        return AttachmentCrypto.encryptedSize(plainSize)
    }
}

/** Whether an incoming photo or voice message is downloaded without a tap. Files are never fetched automatically. */
object AutoFetchPolicy {
    const val OPEN_CHAT_MAX_BYTES = 4L * 1024 * 1024
    const val SMALL_MAX_BYTES = 1L * 1024 * 1024
    const val BACKGROUND_BATCH = 8

    /** [chatOpen]: the conversation of the message is on screen. [metered]: the active network charges for data. */
    data class Context(val chatOpen: Boolean, val metered: Boolean)

    fun shouldFetch(type: AttachmentPayload.Type, blobSize: Long, context: Context): Boolean {
        if (type == AttachmentPayload.Type.FILE) return false
        val limit = when {
            context.chatOpen && !context.metered -> OPEN_CHAT_MAX_BYTES
            context.chatOpen || !context.metered -> SMALL_MAX_BYTES
            else -> return false
        }
        return blobSize <= limit
    }
}
