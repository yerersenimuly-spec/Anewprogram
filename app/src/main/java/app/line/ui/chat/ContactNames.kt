package app.line.ui.chat

import android.content.Context
import java.text.Normalizer

/** Local contact names. Stored in the `line-ui` preferences under `contact-<number>`, the key 0.7.1 already used. */
object ContactNames {
    const val MAX_CODE_POINTS = 40
    private val spaces = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

    /** Normalised, control-free, at most [MAX_CODE_POINTS] code points; blank input clears the name. */
    fun clean(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(spaces, " ").trim()
        val builder = StringBuilder(normalized.length)
        var count = 0
        var index = 0
        while (index < normalized.length && count < MAX_CODE_POINTS) {
            val cp = normalized.codePointAt(index)
            index += Character.charCount(cp)
            val type = Character.getType(cp).toByte()
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.SURROGATE) continue
            builder.appendCodePoint(cp)
            count++
        }
        return builder.toString().trim()
    }

    fun key(peer: String) = "contact-$peer"

    fun get(context: Context, peer: String): String =
        context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).getString(key(peer), null).orEmpty()

    fun save(context: Context, peer: String, raw: String) {
        val value = clean(raw)
        val edit = context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).edit()
        if (value.isEmpty()) edit.remove(key(peer)) else edit.putString(key(peer), value)
        edit.apply()
    }
}
