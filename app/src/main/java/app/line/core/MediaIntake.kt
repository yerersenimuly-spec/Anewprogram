package app.line.core

import org.json.JSONObject
import java.util.Locale

/**
 * What the receiver does with a decrypted `"kind":"media"` payload. Anything it cannot use is [Unsupported]: the
 * envelope is still consumed and shown as a placeholder, because refusing it would roll the ratchet back and make the
 * server redeliver the same poison message forever.
 */
sealed interface MediaIntake {
    /** [descriptor] is the canonical JSON kept as the sealed message text; [payload] has a lower-case blob id. */
    class Accepted(val payload: AttachmentPayload, val descriptor: String) : MediaIntake

    data object Unsupported : MediaIntake

    companion object {
        fun classify(json: JSONObject, envelopeId: String): MediaIntake {
            val parsed = try {
                if (json.optString("id") != envelopeId) return Unsupported
                AttachmentPayload.fromJson(json)
            } catch (e: Exception) {
                return Unsupported
            }
            if (AttachmentCrypto.encryptedSize(parsed.plainSize) != parsed.size) return Unsupported
            val payload = parsed.copy(blobId = parsed.blobId.lowercase(Locale.ROOT))
            val descriptor = try {
                payload.toJson(envelopeId).toString()
            } catch (e: InvalidAttachmentException) {
                return Unsupported
            }
            return Accepted(payload, descriptor)
        }

        /** The descriptor stored in a message of kind image, voice or file, or null when it does not parse. */
        fun descriptor(text: String): AttachmentPayload? = try {
            AttachmentPayload.fromJsonOrNull(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }
}

/** Language-neutral label of an attachment message for notifications and previews. */
object MediaPreview {
    /** `media:image`, `media:voice` or `media:file`; derived from [kind] when the descriptor cannot be read. */
    fun key(kind: String, text: String): String? {
        MediaIntake.descriptor(text)?.let { return it.previewKey() }
        return AttachmentPayload.Type.parse(kind)?.let { "media:${it.wire}" }
    }

    /** The caption of a photo, when it has one. */
    fun caption(text: String): String? = MediaIntake.descriptor(text)?.caption
}
