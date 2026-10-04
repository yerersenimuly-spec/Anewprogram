package app.line.ui.onboarding

import app.line.CallState
import app.line.Link

enum class Step { CODE, CONNECT, NUMBER, NAME, NOTIFICATIONS }

enum class ConnectPhase { CONNECTING, OFFLINE, FAILED, READY }

object OnboardingFlow {
    /** After this long without a connection the screen offers a retry; the service keeps trying in the background. */
    const val FAIL_AFTER_MS = 15_000L

    fun phase(state: CallState, elapsedMs: Long): ConnectPhase = when {
        state.online && state.number.isNotEmpty() -> ConnectPhase.READY
        state.link == Link.WAITING_NETWORK -> ConnectPhase.OFFLINE
        elapsedMs >= FAIL_AFTER_MS -> ConnectPhase.FAILED
        else -> ConnectPhase.CONNECTING
    }

    /** The notification step exists only where the system asks for the permission and it is still missing. */
    fun next(step: Step, askNotifications: Boolean): Step? = when (step) {
        Step.CODE -> Step.CONNECT
        Step.CONNECT -> Step.NUMBER
        Step.NUMBER -> Step.NAME
        Step.NAME -> if (askNotifications) Step.NOTIFICATIONS else null
        Step.NOTIFICATIONS -> null
    }

    /** Back is meaningful only while the code can still be changed. */
    fun back(step: Step): Step? = if (step == Step.CONNECT) Step.CODE else null
}
