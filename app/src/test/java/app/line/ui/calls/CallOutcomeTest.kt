package app.line.ui.calls

import app.line.Notice
import app.line.core.CallSummary
import org.junit.Assert.*
import org.junit.Test

class CallOutcomeTest {
    private fun summary(outcome: String, duration: Long = 0, incoming: Boolean = false, peers: List<String> = listOf("22222222")) =
        CallSummary("id", peers, incoming, outcome, duration, 1_000, 2_000)

    @Test fun kindFollowsOutcomeAndTalkTime() {
        assertEquals(OutcomeKind.COMPLETED, CallOutcomes.kind("completed", 30))
        assertEquals(OutcomeKind.COMPLETED, CallOutcomes.kind("failed", 12, Notice.CALL_NETWORK_LOST))
        assertEquals(OutcomeKind.MISSED, CallOutcomes.kind("missed", 0))
        assertEquals(OutcomeKind.DECLINED, CallOutcomes.kind("declined", 0))
        assertEquals(OutcomeKind.CANCELLED, CallOutcomes.kind("cancelled", 0))
        assertEquals(OutcomeKind.NO_ANSWER, CallOutcomes.kind("failed", 0, Notice.CALL_NO_ANSWER))
        assertEquals(OutcomeKind.FAILED, CallOutcomes.kind("failed", 0))
        assertEquals(OutcomeKind.FAILED, CallOutcomes.kind("something-new", 0))
    }

    @Test fun onlyMissedAndFailedAreNegativeInLists() {
        assertTrue(CallOutcomes.isNegative(OutcomeKind.MISSED))
        assertTrue(CallOutcomes.isNegative(OutcomeKind.FAILED))
        assertFalse(CallOutcomes.isNegative(OutcomeKind.DECLINED))
        assertFalse(CallOutcomes.isNegative(OutcomeKind.COMPLETED))
    }

    @Test fun completedCallShowsDurationAndEncryption() {
        val model = CallOutcomes.summary(summary("completed", 75), Notice.CALL_ENDED)
        assertEquals(OutcomeKind.COMPLETED, model.kind)
        assertTrue(model.showDuration); assertTrue(model.secure)
        assertEquals(Tone.POSITIVE, model.tone)
        assertNull(model.reason)
        assertEquals(PrimaryAction.CALL_AGAIN, model.action)
        assertTrue(model.canMessage)
    }

    @Test fun droppedCallKeepsItsDurationAndExplainsWhy() {
        val model = CallOutcomes.summary(summary("failed", 40, incoming = true), Notice.CALL_NETWORK_LOST)
        assertEquals(OutcomeKind.COMPLETED, model.kind)
        assertEquals(Notice.CALL_NETWORK_LOST, model.reason)
        assertEquals(PrimaryAction.CALL_BACK, model.action)
    }

    @Test fun missedCallOffersCallBackWithoutDuration() {
        val model = CallOutcomes.summary(summary("missed", incoming = true), Notice.NONE)
        assertEquals(OutcomeKind.MISSED, model.kind)
        assertFalse(model.showDuration); assertFalse(model.secure)
        assertEquals(Tone.NEGATIVE, model.tone)
        assertEquals(PrimaryAction.CALL_BACK, model.action)
    }

    @Test fun unansweredAndFailedOutgoingCallsOfferRetry() {
        val none = CallOutcomes.summary(summary("failed"), Notice.CALL_NO_ANSWER)
        assertEquals(OutcomeKind.NO_ANSWER, none.kind)
        assertEquals(Tone.NEUTRAL, none.tone)
        assertEquals(PrimaryAction.TRY_AGAIN, none.action)
        assertNull(none.reason)

        val failed = CallOutcomes.summary(summary("failed"), Notice.CALL_MEDIA_FAILED)
        assertEquals(OutcomeKind.FAILED, failed.kind)
        assertEquals(Notice.CALL_MEDIA_FAILED, failed.reason)
        assertEquals(PrimaryAction.TRY_AGAIN, failed.action)
    }

    @Test fun unrelatedNoticesAreNotMistakenForAReason() {
        val model = CallOutcomes.summary(summary("failed"), Notice.MESSAGE_REJECTED)
        assertEquals(OutcomeKind.FAILED, model.kind)
        assertNull(model.reason)
    }

    @Test fun declinedOutgoingCallIsNeutral() {
        val model = CallOutcomes.summary(summary("declined"), Notice.CALL_DECLINED)
        assertEquals(OutcomeKind.DECLINED, model.kind)
        assertEquals(Tone.NEUTRAL, model.tone)
        assertEquals(PrimaryAction.CALL_AGAIN, model.action)
    }

    @Test fun groupCallsCannotBeMessagedAsOneChat() {
        assertFalse(CallOutcomes.summary(summary("completed", 10, peers = listOf("22222222", "33333333")), Notice.NONE).canMessage)
    }
}
