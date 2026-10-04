package app.line.ui.calls

import app.line.core.NumberInput

/** Formatting shared by every call surface. Pure, so it is covered by JVM tests. */
object CallFormat {
    /** `12345678` becomes `1234 5678`; partial input keeps the same grouping. */
    fun number(digits: String): String =
        if (digits.length <= 4) digits else digits.substring(0, 4) + " " + digits.substring(4)

    fun numbers(numbers: List<String>): String = numbers.joinToString(", ", transform = ::number)

    /** `mm:ss`, or `h:mm:ss` from one hour. Locale independent, so digits stay Latin and tabular. */
    fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        val tail = (if (m < 10) "0$m" else "$m") + ":" + (if (s < 10) "0$s" else "$s")
        return if (h > 0) "$h:$tail" else tail
    }

    fun elapsedSeconds(connectedAt: Long, now: Long): Long =
        if (connectedAt <= 0L) 0L else ((now - connectedAt) / 1_000).coerceAtLeast(0)

    /** Participants of the call other than the user, from the roster the service keeps. */
    fun peers(members: List<String>, own: String, fallback: String): List<String> {
        val others = members.filter { it != own }
        if (others.isNotEmpty()) return others
        return fallback.split(',').map(NumberInput::clean).filter { it.isNotEmpty() }
    }
}

/** What to show for one person: the best known name and whether it is a real name or the number. */
data class PeerLabel(val text: String, val named: Boolean)

object PeerLabels {
    fun resolve(number: String, displayName: (String) -> String): PeerLabel {
        val shown = displayName(number)
        return PeerLabel(shown, shown != CallFormat.number(number) && shown != number)
    }

    /** Names for a group, comma separated; the roster is short (at most seven peers). */
    fun joined(numbers: List<String>, displayName: (String) -> String): String =
        numbers.joinToString(", ") { displayName(it) }
}
