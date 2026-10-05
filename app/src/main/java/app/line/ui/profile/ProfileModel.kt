package app.line.ui.profile

import app.line.CallState
import app.line.Link
import app.line.core.DisplayName

/** Live validation of the profile name field. */
sealed interface NameCheck {
    data object Unchanged : NameCheck
    data class Valid(val name: String) : NameCheck
    data class Invalid(val tooLong: Boolean) : NameCheck
}

object NameRules {
    const val MAX = DisplayName.MAX_CODE_POINTS

    fun check(raw: String, current: String): NameCheck {
        val normalized = DisplayName.normalize(raw)
            ?: return NameCheck.Invalid(tooLong = raw.trim().let { it.codePointCount(0, it.length) } > MAX)
        return if (normalized == current) NameCheck.Unchanged else NameCheck.Valid(normalized)
    }

    fun length(raw: String): Int = raw.trim().let { it.codePointCount(0, it.length) }
}

enum class ConnectionStatus { NOT_CONFIGURED, ONLINE, CONNECTING, WAITING_NETWORK }

object ProfileStatus {
    fun connection(state: CallState): ConnectionStatus = when {
        !state.configReady -> ConnectionStatus.NOT_CONFIGURED
        state.link == Link.ONLINE && state.online -> ConnectionStatus.ONLINE
        state.link == Link.WAITING_NETWORK -> ConnectionStatus.WAITING_NETWORK
        else -> ConnectionStatus.CONNECTING
    }
}

enum class PushStatus { NO_DISTRIBUTOR, NOT_SELECTED, WAITING, ACTIVE, FAILED, SERVER_UNSUPPORTED }

object PushStates {
    fun of(
        distributors: List<String>,
        selected: String?,
        hasEndpoint: Boolean,
        failure: String?,
        active: Boolean,
        online: Boolean,
        serverSupports: Boolean,
    ): PushStatus = when {
        distributors.isEmpty() -> PushStatus.NO_DISTRIBUTOR
        selected == null || selected !in distributors -> PushStatus.NOT_SELECTED
        active -> PushStatus.ACTIVE
        failure != null && !hasEndpoint -> PushStatus.FAILED
        online && !serverSupports -> PushStatus.SERVER_UNSUPPORTED
        else -> PushStatus.WAITING
    }
}

object Languages {
    /** Language names are always shown in their own language, so a user can find theirs after a wrong choice. */
    fun nativeName(code: String): String = when (code) {
        "ru" -> "Русский"
        "en" -> "English"
        "kk" -> "Қазақша"
        else -> code
    }
}
