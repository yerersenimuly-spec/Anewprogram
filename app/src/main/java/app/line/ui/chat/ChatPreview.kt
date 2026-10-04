package app.line.ui.chat

import app.line.core.MessageStatus
import org.json.JSONObject

/** What a dialog row shows for its last message, independent of the display language. */
object ChatPreview {
    enum class Kind { TEXT, IMAGE, VOICE, FILE }

    /** [text] is a one-line snippet for text messages and the caption (possibly empty) for media. */
    data class Preview(val kind: Kind, val text: String)

    private const val SNIPPET_LIMIT = 160

    fun kindOf(wire: String): Kind = when (wire) {
        "image" -> Kind.IMAGE
        "voice" -> Kind.VOICE
        "file" -> Kind.FILE
        else -> Kind.TEXT
    }

    /** [body] is the stored text; for media messages it is the attachment descriptor JSON. */
    fun of(wireKind: String, body: String): Preview {
        val kind = kindOf(wireKind)
        return if (kind == Kind.TEXT) Preview(kind, oneLine(body)) else Preview(kind, caption(body))
    }

    fun oneLine(text: String, limit: Int = SNIPPET_LIMIT): String {
        val builder = StringBuilder(minOf(text.length, limit))
        var pendingSpace = false
        for (char in text) {
            if (char.isWhitespace()) {
                pendingSpace = builder.isNotEmpty()
                continue
            }
            if (pendingSpace) { builder.append(' '); pendingSpace = false }
            builder.append(char)
            if (builder.length >= limit) break
        }
        return builder.toString()
    }

    private fun caption(descriptor: String): String = try {
        oneLine(JSONObject(descriptor).optString("caption", ""))
    } catch (_: Exception) {
        ""
    }
}

/** Which delivery marker a message shows and whether tapping it can do something. */
object Delivery {
    /** Only your own messages carry a marker. */
    fun marker(outgoing: Boolean, status: MessageStatus): MessageStatus? = if (outgoing) status else null

    fun canRetry(outgoing: Boolean, status: MessageStatus): Boolean = outgoing && status == MessageStatus.FAILED

    fun iconName(status: MessageStatus): String = when (status) {
        MessageStatus.PENDING -> "clock"
        MessageStatus.SENT, MessageStatus.RECEIVED -> "check"
        MessageStatus.DELIVERED, MessageStatus.READ -> "check_double"
        MessageStatus.FAILED -> "alert"
    }
}
