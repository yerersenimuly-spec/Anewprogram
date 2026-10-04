package app.line.ui.chat

import android.content.Context
import android.text.format.DateFormat
import app.line.R
import app.line.crypto.ChatMessage
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Localized, thread-safe formatting for chat screens (dates, sizes, durations, previews). Create once per screen. */
class ChatFormat(private val context: Context) {
    val zone: ZoneId = ZoneId.systemDefault()
    private val locale: Locale = context.resources.configuration.locales[0]
    private val time = formatter(if (DateFormat.is24HourFormat(context)) "Hm" else "hm", if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    private val weekday = formatter("EEE", "EEE")
    private val dayMonth = formatter("dMMM", "d MMM")
    private val dayMonthLong = formatter("dMMMM", "d MMMM")
    private val dayMonthYearLong = formatter("dMMMMy", "d MMMM yyyy")
    private val shortDate = formatter("yMMdd", "dd.MM.yy")
    private val sizeNumbers = arrayOf(NumberFormat.getNumberInstance(locale), NumberFormat.getNumberInstance(locale)).also {
        it[0].maximumFractionDigits = 0
        it[1].minimumFractionDigits = 1; it[1].maximumFractionDigits = 1
    }

    private fun formatter(skeleton: String, fallback: String): DateTimeFormatter = try {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    } catch (_: IllegalArgumentException) {
        DateTimeFormatter.ofPattern(fallback, locale)
    }

    fun time(millis: Long): String = time.format(java.time.Instant.ofEpochMilli(millis).atZone(zone))

    /** Stamp of a dialog in the chat list. */
    fun listStamp(millis: Long, now: Long): String {
        val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        return when (ChatDates.listBucket(now, millis, zone)) {
            ChatDates.Bucket.TODAY -> time.format(at)
            ChatDates.Bucket.YESTERDAY -> context.getString(R.string.date_yesterday)
            ChatDates.Bucket.THIS_WEEK -> weekday.format(at)
            ChatDates.Bucket.THIS_YEAR -> dayMonth.format(at)
            ChatDates.Bucket.OLDER -> shortDate.format(at)
        }
    }

    /** Day separator inside a conversation. */
    fun separator(millis: Long, now: Long): String {
        val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        return when (ChatDates.separatorBucket(now, millis, zone)) {
            ChatDates.Bucket.TODAY -> context.getString(R.string.date_today)
            ChatDates.Bucket.YESTERDAY -> context.getString(R.string.date_yesterday)
            ChatDates.Bucket.OLDER -> dayMonthYearLong.format(at)
            else -> dayMonthLong.format(at)
        }
    }

    fun size(bytes: Long): String {
        val size = SizeFormat.of(bytes)
        val number = synchronized(sizeNumbers) { sizeNumbers[if (size.fractionDigits == 0) 0 else 1].format(size.value) }
        return when (size.unit) {
            SizeFormat.Unit.B -> context.getString(R.string.size_b, bytes)
            SizeFormat.Unit.KB -> context.getString(R.string.size_kb, number)
            SizeFormat.Unit.MB -> context.getString(R.string.size_mb, number)
        }
    }

    fun speed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(speed) + "×"

    /** The label of a media message in lists and notifications. */
    fun kindLabel(kind: ChatPreview.Kind): String = when (kind) {
        ChatPreview.Kind.IMAGE -> context.getString(R.string.preview_photo)
        ChatPreview.Kind.VOICE -> context.getString(R.string.preview_voice)
        ChatPreview.Kind.FILE -> context.getString(R.string.preview_file)
        ChatPreview.Kind.TEXT -> ""
    }

    /** One-line preview of a message: text, or the media label followed by its caption. */
    fun previewLine(message: ChatMessage): String {
        val preview = ChatPreview.of(message.kind, message.text)
        if (preview.kind == ChatPreview.Kind.TEXT) return preview.text
        val label = kindLabel(preview.kind)
        return if (preview.text.isEmpty()) label else "$label, ${preview.text}"
    }
}

/** "1234 5678". */
fun String.spacedNumber(): String = NumberEntry.spaced(this)

/** Name shown for a peer, or null when only the number is known (so avatars fall back to the silhouette). */
fun app.line.CallService?.knownName(peer: String): String? {
    val name = this?.displayName(peer) ?: return null
    return name.takeIf { it != peer.spacedNumber() }
}
