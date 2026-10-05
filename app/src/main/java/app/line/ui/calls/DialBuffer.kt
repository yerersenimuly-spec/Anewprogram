package app.line.ui.calls

import app.line.CallState
import app.line.Phase
import app.line.core.NumberInput

/**
 * The number being typed on the keypad: one or more eight-digit numbers. The last segment is the one being edited,
 * and may be empty right after "add participant".
 */
class DialBuffer private constructor(val segments: List<String>) {
    constructor() : this(listOf(""))

    val isEmpty: Boolean get() = segments.size == 1 && segments[0].isEmpty()
    val current: String get() = segments.last()

    fun digit(value: Char): DialBuffer {
        if (value !in '0'..'9' || current.length >= NUMBER_LENGTH) return this
        return DialBuffer(segments.dropLast(1) + (current + value))
    }

    fun backspace(): DialBuffer = when {
        current.isNotEmpty() -> DialBuffer(segments.dropLast(1) + current.dropLast(1))
        segments.size > 1 -> DialBuffer(segments.dropLast(1))
        else -> this
    }

    fun clear(): DialBuffer = DialBuffer()

    fun canAdd(maxPeers: Int): Boolean = current.length == NUMBER_LENGTH && segments.size < maxPeers

    fun add(maxPeers: Int): DialBuffer = if (canAdd(maxPeers)) DialBuffer(segments + "") else this

    /** Complete numbers only; a half-typed one is not a number yet. */
    fun numbers(): List<String> = segments.filter { it.isNotEmpty() }

    fun display(): String {
        val formatted = segments.map(CallFormat::number)
        return if (segments.size > 1 && current.isEmpty()) formatted.dropLast(1).joinToString(", ") + ","
        else formatted.joinToString(", ")
    }

    /** A problem that is already certain while typing: own number or a repeated one. Incomplete numbers are not judged. */
    fun liveProblem(own: String): NumberInput.Problem? {
        val complete = segments.filter { it.length == NUMBER_LENGTH }
        if (own.isNotEmpty() && own in complete) return NumberInput.Problem.OWN
        if (complete.toSet().size != complete.size) return NumberInput.Problem.DUPLICATE
        return null
    }

    fun validate(own: String, maxPeers: Int): NumberInput.Result =
        NumberInput.list(numbers().joinToString(","), own, maxPeers)

    override fun equals(other: Any?): Boolean = other is DialBuffer && other.segments == segments
    override fun hashCode(): Int = segments.hashCode()

    companion object {
        const val NUMBER_LENGTH = 8
        private val digitsRun = Regex("[0-9]{8}")

        /** Takes the eight-digit numbers out of pasted text; anything else is ignored. */
        fun fromText(text: String, maxPeers: Int): DialBuffer {
            val compact = text.replace(Regex("[\\s\\u00A0-]"), "")
            val found = digitsRun.findAll(compact).map { it.value }.distinct().take(maxPeers).toList()
            if (found.isEmpty()) {
                val digits = compact.filter { it in '0'..'9' }.take(NUMBER_LENGTH)
                return DialBuffer(listOf(digits))
            }
            return DialBuffer(found)
        }
    }
}

/** Why the call button cannot be used right now. */
enum class DialBlock { NONE, NOT_CONFIGURED, IN_CALL, CALLS_DISABLED, MEDIA_UNAVAILABLE }

object DialAvailability {
    fun of(state: CallState): DialBlock = when {
        !state.configReady -> DialBlock.NOT_CONFIGURED
        state.phase != Phase.IDLE -> DialBlock.IN_CALL
        !state.callsEnabled -> DialBlock.CALLS_DISABLED
        state.online && !state.mediaReady -> DialBlock.MEDIA_UNAVAILABLE
        else -> DialBlock.NONE
    }

    /** Participants a user may add: the server limit counts the user too. */
    fun maxPeers(state: CallState): Int = (state.maxParticipants - 1).coerceIn(1, 7)
}
