package app.line.core

import java.util.Locale

/** File names for decrypted copies that are handed to other apps through the FileProvider. */
object SafeFileName {
    private const val MAX_LENGTH = 100
    private const val EXTENSION_MAX = 12
    private val unsafe = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val extensions = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/heic" to "heic",
        "audio/mp4" to "m4a", "audio/mpeg" to "mp3", "audio/ogg" to "ogg", "audio/aac" to "aac", "video/mp4" to "mp4",
        "application/pdf" to "pdf", "application/zip" to "zip", "text/plain" to "txt",
    )

    fun forPayload(payload: AttachmentPayload): String {
        val extension = extensions[payload.mime]
        val fallback = when (payload.type) {
            AttachmentPayload.Type.IMAGE -> "photo"
            AttachmentPayload.Type.VOICE -> "voice"
            AttachmentPayload.Type.FILE -> "file"
        }
        val cleaned = clean(payload.name) ?: fallback
        return if (extension != null && !cleaned.contains('.')) "$cleaned.$extension" else cleaned
    }

    /** One path component without separators, controls, leading dots or trailing dots and spaces; null if nothing is left. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        var name = raw.replace(unsafe, "_").trim().trimStart('.').trimEnd('.', ' ')
        if (name.isEmpty() || name.all { it == '_' }) return null
        if (name.length > MAX_LENGTH) {
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && name.length - dot <= EXTENSION_MAX) name.substring(dot).lowercase(Locale.ROOT) else ""
            var keep = MAX_LENGTH - extension.length
            if (Character.isHighSurrogate(name[keep - 1])) keep--
            name = name.substring(0, keep).trimEnd('.', ' ') + extension
        }
        return name
    }
}
