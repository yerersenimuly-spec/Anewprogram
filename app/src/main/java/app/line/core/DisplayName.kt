package app.line.core

import java.text.Normalizer

/** Profile display names: same rules as the server (`profile_set`), so a name accepted here is accepted there. */
object DisplayName {
    const val MAX_CODE_POINTS = 32
    private val spaces = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

    /** Returns the normalised name ("" clears it) or null when the input is not acceptable. */
    fun normalize(raw: String): String? {
        val collapsed = Normalizer.normalize(raw, Normalizer.Form.NFC).replace(spaces, " ").trim()
        if (collapsed.codePointCount(0, collapsed.length) > MAX_CODE_POINTS) return null
        var index = 0
        while (index < collapsed.length) {
            val cp = collapsed.codePointAt(index)
            if (forbidden(cp)) return null
            index += Character.charCount(cp)
        }
        return collapsed
    }

    private fun forbidden(cp: Int): Boolean {
        if (cp in 0x202A..0x202E || cp in 0x2066..0x2069) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true
            else -> false
        }
    }

    fun initials(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        val letters = words.take(2).mapNotNull { word -> word.codePoints().toArray().firstOrNull()?.let { String(Character.toChars(it)) } }
        return letters.joinToString("").uppercase().ifEmpty { "?" }
    }
}
