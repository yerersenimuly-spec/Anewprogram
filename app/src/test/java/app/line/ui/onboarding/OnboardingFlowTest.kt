package app.line.ui.onboarding

import app.line.CallState
import app.line.Link
import org.junit.Assert.*
import org.junit.Test

class OnboardingFlowTest {
    private val connecting = CallState(configReady = true, link = Link.CONNECTING)

    @Test fun readyOnlyWhenOnlineWithAServerNumber() {
        assertEquals(ConnectPhase.READY, OnboardingFlow.phase(connecting.copy(online = true, link = Link.ONLINE, number = "12345678"), 100))
        assertEquals(ConnectPhase.CONNECTING, OnboardingFlow.phase(connecting.copy(online = true, link = Link.ONLINE), 100))
    }

    @Test fun staysConnectingUntilTheTimeoutThenOffersRetry() {
        assertEquals(ConnectPhase.CONNECTING, OnboardingFlow.phase(connecting, 0))
        assertEquals(ConnectPhase.CONNECTING, OnboardingFlow.phase(connecting, OnboardingFlow.FAIL_AFTER_MS - 1))
        assertEquals(ConnectPhase.FAILED, OnboardingFlow.phase(connecting, OnboardingFlow.FAIL_AFTER_MS))
    }

    @Test fun missingNetworkIsOfflineNotAFailure() {
        val offline = connecting.copy(link = Link.WAITING_NETWORK)
        assertEquals(ConnectPhase.OFFLINE, OnboardingFlow.phase(offline, 0))
        assertEquals(ConnectPhase.OFFLINE, OnboardingFlow.phase(offline, 60_000))
    }

    @Test fun stepsFollowTheFlowAndSkipNotificationsWhenNotNeeded() {
        assertEquals(Step.CONNECT, OnboardingFlow.next(Step.CODE, true))
        assertEquals(Step.NUMBER, OnboardingFlow.next(Step.CONNECT, true))
        assertEquals(Step.NAME, OnboardingFlow.next(Step.NUMBER, true))
        assertEquals(Step.NOTIFICATIONS, OnboardingFlow.next(Step.NAME, true))
        assertNull(OnboardingFlow.next(Step.NAME, false))
        assertNull(OnboardingFlow.next(Step.NOTIFICATIONS, true))
    }

    @Test fun backReturnsToTheCodeOnlyFromConnecting() {
        assertEquals(Step.CODE, OnboardingFlow.back(Step.CONNECT))
        assertNull(OnboardingFlow.back(Step.NUMBER))
        assertNull(OnboardingFlow.back(Step.CODE))
    }
}
