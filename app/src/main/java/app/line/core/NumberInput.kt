package app.line.core

/**
 * Validation of Line numbers typed by the user. A number is eight digits and must belong to somebody else:
 * the user's own number is never a valid chat or call target.
 */
object NumberInput {
    enum class Problem { EMPTY, FORMAT, OWN, DUPLICATE, TOO_MANY }

    sealed interface Result {
        data class Valid(val numbers: List<String>) : Result
        data class Invalid(val problem: Problem) : Result
    }

    private val digits = Regex("[0-9]{8}")

    fun clean(raw: String): String = raw.filter { !it.isWhitespace() && it != '-' && it != '\u00A0' }

    fun single(raw: String, own: String): Result {
        val value = clean(raw)
        return when {
            value.isEmpty() -> Result.Invalid(Problem.EMPTY)
            !digits.matches(value) -> Result.Invalid(Problem.FORMAT)
            own.isNotEmpty() && value == own -> Result.Invalid(Problem.OWN)
            else -> Result.Valid(listOf(value))
        }
    }

    /** Comma-separated participants for a call; at most [max] numbers. */
    fun list(raw: String, own: String, max: Int): Result {
        val parts = raw.split(',').map(::clean).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.Invalid(Problem.EMPTY)
        if (parts.size > max) return Result.Invalid(Problem.TOO_MANY)
        parts.forEach { part ->
            val one = single(part, own)
            if (one is Result.Invalid) return one
        }
        if (parts.toSet().size != parts.size) return Result.Invalid(Problem.DUPLICATE)
        return Result.Valid(parts)
    }
}
