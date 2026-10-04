package app.line.ui.onboarding

import app.line.ConnectionProfile
import app.line.EndpointConfig
import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Turns whatever the user pasted into a validated connection profile, with an actionable reason when it is not one. */
object ConnectionCodes {
    enum class Problem { EMPTY, NOT_A_CODE, TOO_LONG, DAMAGED, UNSUPPORTED, INVALID }

    sealed interface Result {
        data class Valid(val config: EndpointConfig, val host: String, val code: String) : Result
        data class Invalid(val problem: Problem) : Result
    }

    private const val PREFIX = "LINE1."
    private const val MAX_LENGTH = 4096
    private val token = Regex("LINE1\\.[A-Za-z0-9_-]+")
    private val wrapper = Regex("[\\s\\u200B-\\u200D\\uFEFF\"'`<>«»“”]")

    /**
     * Messengers wrap long codes, add quotes or surround them with text. Returns the code itself when one can be found,
     * otherwise the trimmed input so that the validation error describes what the user actually entered.
     */
    fun extract(text: String): String {
        val compact = text.replace(wrapper, "")
        if (compact.startsWith(PREFIX) && token.matchEntire(compact) != null) return compact
        val inline = text.split(Regex("[\\s\"'`<>«»“”]+")).firstOrNull { it.startsWith(PREFIX) }
        if (inline != null) token.find(inline)?.let { return it.value }
        return text.trim()
    }

    fun parse(raw: String): Result {
        val code = extract(raw)
        if (code.isEmpty()) return Result.Invalid(Problem.EMPTY)
        if (!code.startsWith(PREFIX)) return Result.Invalid(Problem.NOT_A_CODE)
        if (code.length > MAX_LENGTH) return Result.Invalid(Problem.TOO_LONG)
        val json = runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)), Charsets.UTF_8))
        }.getOrNull() ?: return Result.Invalid(Problem.DAMAGED)
        if (json.optInt("version") != 1) return Result.Invalid(Problem.UNSUPPORTED)
        val config = runCatching { ConnectionProfile.decode(code) }.getOrNull() ?: return Result.Invalid(Problem.INVALID)
        val host = hostOf(config) ?: return Result.Invalid(Problem.INVALID)
        return Result.Valid(config, host, code)
    }

    fun hostOf(config: EndpointConfig): String? = runCatching { URI(config.apiUrl).host }.getOrNull()?.takeIf { it.isNotEmpty() }
}
