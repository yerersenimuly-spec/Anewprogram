package app.line.core

import org.json.JSONObject
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/** A media payload that breaks the wire format; see [AttachmentPayload.fromJson]. */
class InvalidAttachmentException(message: String) : IllegalArgumentException(message)

/**
 * One end-to-end encrypted attachment announced inside a Signal message (`"kind":"media"`, `"v":2`).
 * The ciphertext lives in a server blob; [key] never leaves the Signal envelope. [size] is the blob length
 * ([AttachmentCrypto.encryptedSize] of [plainSize]), [plainSize] the decrypted length.
 */
data class AttachmentPayload(
    val type: Type,
    val blobId: String,
    val key: ByteArray,
    val size: Long,
    val plainSize: Long,
    val mime: String,
    val name: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val waveform: ByteArray?,
    val thumb: ByteArray?,
    val caption: String?,
) {
    enum class Type(val wire: String) {
        IMAGE("image"),
        VOICE("voice"),
        FILE("file");

        companion object {
            fun parse(wire: String?): Type? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** Message kind stored in the local database: `image`, `voice` or `file`. */
    fun displayKind(): String = type.wire

    /**
     * Language-neutral token for chat-list previews and notifications (`media:image`, `media:voice`, `media:file`);
     * the UI maps it to a localised label.
     */
    fun previewKey(): String = "media:${type.wire}"

    /** Serialises to the plaintext Signal payload. Throws [InvalidAttachmentException] for anything a peer would reject. */
    fun toJson(messageId: String): JSONObject {
        val json = JSONObject()
            .put("kind", KIND).put("id", messageId).put("v", VERSION).put("type", type.wire)
            .put("blob", blobId).put("key", encode(key)).put("size", size).put("psize", plainSize).put("mime", mime)
        sanitizeName(name)?.let { json.put("name", it) }
        if (width != null) json.put("w", width)
        if (height != null) json.put("h", height)
        if (durationMs != null) json.put("dur", durationMs)
        if (waveform != null) json.put("wave", encode(waveform))
        if (thumb != null) json.put("thumb", encode(thumb))
        sanitizeCaption(caption)?.let { json.put("caption", it) }
        fromJson(json)
        if (json.toString().toByteArray(Charsets.UTF_8).size >= MAX_JSON_BYTES) reject("payload does not fit in 12 KiB")
        return json
    }

    override fun equals(other: Any?): Boolean = other is AttachmentPayload && type == other.type &&
        blobId == other.blobId && key.contentEquals(other.key) && size == other.size && plainSize == other.plainSize &&
        mime == other.mime && name == other.name && width == other.width && height == other.height &&
        durationMs == other.durationMs && waveform.contentEquals(other.waveform) && thumb.contentEquals(other.thumb) &&
        caption == other.caption

    override fun hashCode(): Int {
        var hash = type.hashCode()
        hash = 31 * hash + blobId.hashCode()
        hash = 31 * hash + key.contentHashCode()
        hash = 31 * hash + size.hashCode()
        hash = 31 * hash + plainSize.hashCode()
        hash = 31 * hash + mime.hashCode()
        hash = 31 * hash + (name?.hashCode() ?: 0)
        hash = 31 * hash + (width ?: 0)
        hash = 31 * hash + (height ?: 0)
        hash = 31 * hash + (durationMs?.hashCode() ?: 0)
        hash = 31 * hash + (waveform?.contentHashCode() ?: 0)
        hash = 31 * hash + (thumb?.contentHashCode() ?: 0)
        hash = 31 * hash + (caption?.hashCode() ?: 0)
        return hash
    }

    /** The key is a secret: it must never reach logs. */
    override fun toString(): String =
        "AttachmentPayload(type=$type, blobId=$blobId, size=$size, plainSize=$plainSize, mime=$mime, key=<redacted>)"

    companion object {
        const val KIND = "media"
        const val VERSION = 2
        const val MAX_BLOB_BYTES = 64L * 1024 * 1024
        const val MAX_DURATION_MS = 2L * 60 * 60 * 1000
        const val MAX_WAVEFORM_BYTES = 128
        const val MAX_THUMB_BYTES = 4096
        const val MAX_CAPTION_BYTES = 1024
        const val MAX_NAME_CHARS = 120
        const val MAX_MIME_CHARS = 100
        const val MAX_DIMENSION = 16384
        const val MAX_JSON_BYTES = 12 * 1024
        const val DEFAULT_MIME = "application/octet-stream"
        private const val NAME_EXTENSION_CHARS = 12

        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val mimePattern = Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")
        private val spaces = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")

        /**
         * Parses and validates the plaintext of a `media` message. Rejects (with [InvalidAttachmentException]) a wrong
         * kind or version, ids that are not UUIDs, a key that is not 32 bytes, a blob size outside 1..64 MiB, a mime type
         * that is not `type/subtype` in lower case (at most 100 characters), unknown types, durations over 2 h, waveforms
         * over 128 bytes, thumbnails over 4096 bytes, captions over 1024 bytes of UTF-8 and mistyped fields. Names and
         * captions are sanitised rather than rejected. Unknown extra fields are ignored.
         */
        fun fromJson(json: JSONObject): AttachmentPayload {
            if (string(json, "kind") != KIND) reject("not a media payload")
            if (integer(json, "v") != VERSION.toLong()) reject("unsupported media version")
            if (!isUuid(string(json, "id"))) reject("id is not a UUID")
            val type = Type.parse(string(json, "type")) ?: reject("unknown type")
            val blob = string(json, "blob")
            if (blob == null || !isUuid(blob)) reject("blob is not a UUID")
            val key = decode("key", string(json, "key") ?: reject("key is missing"), AttachmentCrypto.KEY_SIZE)
            if (key.size != AttachmentCrypto.KEY_SIZE) reject("key must be ${AttachmentCrypto.KEY_SIZE} bytes")
            val size = integer(json, "size") ?: reject("size is missing")
            if (size <= 0 || size > MAX_BLOB_BYTES) reject("size is out of range")
            val plainSize = integer(json, "psize") ?: reject("psize is missing")
            if (plainSize < 0 || plainSize > size) reject("psize is out of range")
            val mime = string(json, "mime") ?: reject("mime is missing")
            if (mime.length > MAX_MIME_CHARS || !mimePattern.matches(mime)) reject("mime is invalid")
            val width = integer(json, "w")
            val height = integer(json, "h")
            if ((width == null) != (height == null)) reject("w and h come together")
            if (width != null && height != null) {
                if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) reject("dimensions are out of range")
            }
            val duration = integer(json, "dur")
            if (duration != null && (duration < 0 || duration > MAX_DURATION_MS)) reject("duration is out of range")
            val waveform = string(json, "wave")?.let { decode("wave", it, MAX_WAVEFORM_BYTES) }
                ?.let { wave -> ByteArray(wave.size) { wave[it].toInt().coerceIn(0, Waveform.PEAK).toByte() } }
            val thumb = string(json, "thumb")?.let { decode("thumb", it, MAX_THUMB_BYTES) }
            val caption = sanitizeCaption(string(json, "caption"))
            if (caption != null && caption.toByteArray(Charsets.UTF_8).size > MAX_CAPTION_BYTES) reject("caption is too long")
            return AttachmentPayload(
                type, blob, key, size, plainSize, mime, sanitizeName(string(json, "name")),
                width?.toInt(), height?.toInt(), duration, waveform, thumb, caption,
            )
        }

        fun fromJsonOrNull(json: JSONObject): AttachmentPayload? = try {
            fromJson(json)
        } catch (e: InvalidAttachmentException) {
            null
        }

        /** Lower-cased `type/subtype` without parameters, or [DEFAULT_MIME] when [raw] is missing or malformed. */
        fun normalizeMime(raw: String?): String {
            val base = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            return if (base != null && base.length <= MAX_MIME_CHARS && mimePattern.matches(base)) base else DEFAULT_MIME
        }

        /**
         * A safe display name: the last path component, without control, bidirectional-override and zero-width
         * characters, NFC-normalised, whitespace collapsed, at most [MAX_NAME_CHARS] characters with the extension
         * kept. Null when nothing usable is left.
         */
        fun sanitizeName(raw: String?): String? {
            if (raw == null) return null
            val leaf = raw.split('/', '\\').lastOrNull { it.isNotBlank() } ?: return null
            val cleaned = StringBuilder(leaf.length)
            forEachCodePoint(leaf) { if (!unsafeInName(it)) cleaned.appendCodePoint(it) }
            val name = Normalizer.normalize(cleaned, Normalizer.Form.NFC).replace(spaces, " ").trim()
            if (name.isEmpty() || name == "." || name == "..") return null
            return truncateName(name)
        }

        /** Caption without control characters (newlines and tabs stay); null when blank. */
        fun sanitizeCaption(raw: String?): String? {
            if (raw == null) return null
            val cleaned = StringBuilder(raw.length)
            forEachCodePoint(raw.replace("\r\n", "\n").replace('\r', '\n')) {
                if (it == '\n'.code || it == '\t'.code || !(Character.isISOControl(it) || it in 0xD800..0xDFFF)) {
                    cleaned.appendCodePoint(it)
                }
            }
            return cleaned.toString().takeIf { it.isNotBlank() }
        }

        private fun isUuid(value: String?): Boolean = value != null && uuid.matches(value)

        private fun reject(message: String): Nothing = throw InvalidAttachmentException(message)

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun decode(field: String, text: String, maxBytes: Int): ByteArray {
            if (text.length > (maxBytes + 2) / 3 * 4) reject("$field is too long")
            val bytes = try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                reject("$field is not valid base64")
            }
            if (bytes.size > maxBytes) reject("$field is too long")
            return bytes
        }

        private fun string(json: JSONObject, key: String): String? {
            if (!json.has(key) || json.isNull(key)) return null
            return json.get(key) as? String ?: reject("$key must be a string")
        }

        private fun integer(json: JSONObject, key: String): Long? {
            if (!json.has(key) || json.isNull(key)) return null
            return when (val value = json.get(key)) {
                is Int -> value.toLong()
                is Long -> value
                else -> reject("$key must be an integer")
            }
        }

        private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                action(codePoint)
            }
        }

        private fun unsafeInName(codePoint: Int): Boolean = Character.isISOControl(codePoint) ||
            codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069 || codePoint in 0xD800..0xDFFF ||
            codePoint == 0x200B || codePoint == 0x200E || codePoint == 0x200F || codePoint == 0x061C ||
            codePoint == 0x2060 || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0xFEFF

        private fun truncateName(name: String): String {
            if (name.length <= MAX_NAME_CHARS) return name
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && name.length - dot <= NAME_EXTENSION_CHARS) name.substring(dot) else ""
            var keep = MAX_NAME_CHARS - extension.length
            if (Character.isHighSurrogate(name[keep - 1])) keep--
            return name.substring(0, keep).trimEnd() + extension
        }
    }
}
