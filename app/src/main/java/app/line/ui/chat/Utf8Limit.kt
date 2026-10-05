package app.line.ui.chat

import android.text.InputFilter
import android.text.Spanned

/** Byte budget of a text in UTF-8, so a message never exceeds what the service accepts. */
object Utf8Limit {
    const val MESSAGE_BYTES = 4_096

    fun bytes(text: CharSequence, start: Int = 0, end: Int = text.length): Int {
        var total = 0
        var index = start
        while (index < end) {
            val cp = Character.codePointAt(text, index)
            total += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            index += Character.charCount(cp)
        }
        return total
    }

    /** How many chars of text[start, end) fit into [budget] bytes without splitting a surrogate pair. */
    fun fit(text: CharSequence, start: Int, end: Int, budget: Int): Int {
        var used = 0
        var index = start
        while (index < end) {
            val cp = Character.codePointAt(text, index)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + size > budget) break
            used += size
            index += Character.charCount(cp)
        }
        return index - start
    }
}

class Utf8LengthFilter(private val maxBytes: Int = Utf8Limit.MESSAGE_BYTES) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        val kept = Utf8Limit.bytes(dest, 0, dstart) + Utf8Limit.bytes(dest, dend, dest.length)
        val budget = maxBytes - kept
        if (Utf8Limit.bytes(source, start, end) <= budget) return null
        val chars = Utf8Limit.fit(source, start, end, budget.coerceAtLeast(0))
        return source.subSequence(start, start + chars)
    }
}
