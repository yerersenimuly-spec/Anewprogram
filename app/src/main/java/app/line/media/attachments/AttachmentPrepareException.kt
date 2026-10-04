package app.line.media.attachments

import app.line.core.AttachmentTooLarge
import app.line.core.AttachmentUnreadable
import app.line.core.AttachmentUnsupported
import app.line.core.MediaLimits
import app.line.core.MediaSendException
import app.line.core.MediaUnavailable
import java.io.IOException

/** An image or file chosen by the user could not be turned into something sendable. */
class AttachmentPrepareException(val reason: Reason, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: reason.name, cause) {
    enum class Reason {
        /** The URI could not be opened or read. */
        UNREADABLE,

        /** Not an image this device can decode, or not a `content://` URI. */
        UNSUPPORTED,

        /** Larger than the allowed size. */
        TOO_LARGE,

        /** Decoding needed more memory than the device could give. */
        OUT_OF_MEMORY,
    }
}

/** What the sender's UI sees for a file or image that could not be prepared. */
fun AttachmentPrepareException.toSendException(): MediaSendException = when (reason) {
    AttachmentPrepareException.Reason.UNREADABLE -> AttachmentUnreadable(this)
    AttachmentPrepareException.Reason.UNSUPPORTED -> AttachmentUnsupported(this)
    AttachmentPrepareException.Reason.TOO_LARGE -> AttachmentTooLarge(MediaLimits.MAX_FILE_BYTES + 1, MediaLimits.MAX_FILE_BYTES)
    AttachmentPrepareException.Reason.OUT_OF_MEMORY -> MediaUnavailable(this)
}
