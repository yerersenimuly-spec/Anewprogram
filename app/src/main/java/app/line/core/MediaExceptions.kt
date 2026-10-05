package app.line.core

import java.io.IOException

/**
 * A send that cannot start. Nothing was queued and no file was kept. The UI maps the subtype to its text:
 * [MediaNotSupportedByPeer] to `media_peer_old`, [AttachmentTooLarge] to `media_too_large`,
 * [AttachmentUnsupported] to `media_unsupported_image`; the rest are generic failures.
 */
sealed class MediaSendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The peer has not shown that it understands attachments (protocol 8 or a payload with `"v"` >= 2). */
class MediaNotSupportedByPeer(val peer: String) : MediaSendException("Peer does not support attachments")

class AttachmentTooLarge(val size: Long, val limit: Long) : MediaSendException("Attachment exceeds $limit bytes")

/** The picked file or recording could not be opened or read. */
class AttachmentUnreadable(cause: Throwable? = null) : MediaSendException("The attachment could not be read", cause)

/** Not an image this device can decode, or not a `content://` URI. */
class AttachmentUnsupported(cause: Throwable? = null) : MediaSendException("The attachment cannot be sent", cause)

/** The encrypted outbox holds [limit] items already; wait for the connection or delete queued messages. */
class OutboxFull(val limit: Int) : MediaSendException("The outbox is full")

/** The connected server cannot store attachments, or the device ran out of memory or storage while preparing. */
class MediaUnavailable(cause: Throwable? = null) : MediaSendException("Attachments are not available", cause)

/** An incoming attachment cannot be opened: it was never fetched, has expired on the server, or failed to download. */
class AttachmentUnavailable(val stage: TransferStage) : IOException("Attachment is not available: $stage")
