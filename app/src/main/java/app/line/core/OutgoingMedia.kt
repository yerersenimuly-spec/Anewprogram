package app.line.core

import java.nio.charset.StandardCharsets

/**
 * What the sender knows about an attachment before it is encrypted. [toPayload] adds the parts that exist only after
 * sealing (blob id, key, size) and produces a payload every peer will accept.
 */
class MediaDraft private constructor(
    private val type: AttachmentPayload.Type,
    private val mime: String,
    private val name: String?,
    private val width: Int?,
    private val height: Int?,
    private val durationMs: Long?,
    private val waveform: ByteArray?,
    private val thumb: ByteArray?,
    private val caption: String?,
) {
    /** Throws [AttachmentTooLarge] when [plainSize] is over [MediaLimits.MAX_FILE_BYTES]. */
    fun toPayload(blobId: String, key: ByteArray, plainSize: Long): AttachmentPayload = AttachmentPayload(
        type, blobId, key, MediaLimits.encryptedSizeOrThrow(plainSize), plainSize, mime, name, width, height, durationMs,
        waveform, thumb, caption,
    )

    companion object {
        fun image(width: Int, height: Int, thumb: ByteArray?, caption: String?, mime: String = "image/jpeg"): MediaDraft {
            val sized = width in 1..AttachmentPayload.MAX_DIMENSION && height in 1..AttachmentPayload.MAX_DIMENSION
            return MediaDraft(
                AttachmentPayload.Type.IMAGE, AttachmentPayload.normalizeMime(mime), null,
                if (sized) width else null, if (sized) height else null, null, null,
                thumb?.takeIf { it.isNotEmpty() && it.size <= AttachmentPayload.MAX_THUMB_BYTES }, caption(caption),
            )
        }

        fun voice(durationMs: Long, amplitudes: IntArray, mime: String): MediaDraft = MediaDraft(
            AttachmentPayload.Type.VOICE, AttachmentPayload.normalizeMime(mime), null, null, null,
            durationMs.coerceIn(0, AttachmentPayload.MAX_DURATION_MS), Waveform.fromAmplitudes(amplitudes), null, null,
        )

        fun file(name: String?, mime: String): MediaDraft = MediaDraft(
            AttachmentPayload.Type.FILE, AttachmentPayload.normalizeMime(mime), AttachmentPayload.sanitizeName(name),
            null, null, null, null, null, null,
        )

        /** Sanitised and cut on a character boundary to the wire limit, so a long caption is shortened instead of rejected. */
        fun caption(raw: String?): String? {
            val clean = AttachmentPayload.sanitizeCaption(raw) ?: return null
            return truncateUtf8(clean.trim(), AttachmentPayload.MAX_CAPTION_BYTES).trim().takeIf { it.isNotEmpty() }
        }

        fun truncateUtf8(text: String, maxBytes: Int): String {
            if (text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return text
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val width = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                if (bytes + width > maxBytes) break
                bytes += width
                end += Character.charCount(codePoint)
            }
            return text.substring(0, end)
        }
    }
}
