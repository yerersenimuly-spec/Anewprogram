package app.line.media.attachments

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
