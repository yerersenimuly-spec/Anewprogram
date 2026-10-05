package app.line.ui.chat

import app.line.core.NumberInput

/** The number field of the new-chat form: spaced display "1234 5678" over eight plain digits. */
object NumberEntry {
    const val LENGTH = 8
    private const val GROUP = 4

    enum class Feedback { NONE, FORMAT, OWN }

    fun digits(raw: String): String = raw.filter { it in '0'..'9' }.take(LENGTH)

    fun spaced(digits: String): String = digits.chunked(GROUP).joinToString(" ")

    /** Reformats [text] with its caret at [cursor]; the caret stays after the same digit. */
    fun reformat(text: String, cursor: Int): Pair<String, Int> {
        val all = digits(text)
        val before = text.take(cursor.coerceIn(0, text.length)).count { it in '0'..'9' }.coerceAtMost(all.length)
        val caret = if (before == 0) 0 else before + (before - 1) / GROUP
        return spaced(all) to caret
    }

    /** What to tell the user about the typed number; empty input is silent. */
    fun feedback(raw: String, own: String): Feedback = when (val result = NumberInput.single(raw, own)) {
        is NumberInput.Result.Valid -> Feedback.NONE
        is NumberInput.Result.Invalid -> when (result.problem) {
            NumberInput.Problem.EMPTY -> Feedback.NONE
            NumberInput.Problem.OWN -> Feedback.OWN
            else -> Feedback.FORMAT
        }
    }

    fun isValid(raw: String, own: String): Boolean = NumberInput.single(raw, own) is NumberInput.Result.Valid
}

/** One-line excerpt of a message around a search hit. */
object Snippet {
    data class Result(val text: String, val matchStart: Int, val matchEnd: Int)

    fun around(text: String, query: String, radius: Int = 40): Result {
        val flat = ChatPreview.oneLine(text, Int.MAX_VALUE)
        val needle = query.trim()
        val hit = if (needle.isEmpty()) -1 else flat.indexOf(needle, ignoreCase = true)
        if (hit < 0) return Result(if (flat.length > radius * 2) flat.take(radius * 2) + "…" else flat, -1, -1)
        val from = (hit - radius).coerceAtLeast(0)
        val to = (hit + needle.length + radius).coerceAtMost(flat.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < flat.length) "…" else ""
        val start = prefix.length + hit - from
        return Result(prefix + flat.substring(from, to) + suffix, start, start + needle.length)
    }
}

/** The safety code as the user compares it: groups of five digits, three per line. */
object SafetyCode {
    fun format(code: String): String = code.filter { it in '0'..'9' }.chunked(5).chunked(3).joinToString("\n") { it.joinToString("  ") }
}
