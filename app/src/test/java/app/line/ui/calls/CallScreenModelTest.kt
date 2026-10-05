package app.line.ui.calls

import app.line.CallState
import app.line.Link
import app.line.Phase
import org.junit.Assert.*
import org.junit.Test

class CallScreenModelTest {
    private val base = CallState(number = "11111111", online = true, link = Link.ONLINE)

    @Test fun idleHasNoScreen() {
        assertNull(CallScreenModel.from(base))
    }

    @Test fun incomingCallKnowsItsCallerAndGroupSize() {
        val model = CallScreenModel.from(base.copy(phase = Phase.INCOMING, peer = "22222222", members = listOf("22222222", "11111111", "33333333")))!!
        assertEquals(CallMode.INCOMING, model.mode)
        assertEquals("22222222", model.caller)
        assertEquals(listOf("22222222", "33333333"), model.peers)
        assertEquals(3, model.groupSize)
        assertTrue(model.group)
        assertEquals(LinkNote.NONE, model.note)
    }

    @Test fun outgoingPeersComeFromTheRosterNotTheDisplayString() {
        val model = CallScreenModel.from(base.copy(phase = Phase.OUTGOING, peer = "22222222, 33333333", members = listOf("11111111", "22222222", "33333333")))!!
        assertEquals(listOf("22222222", "33333333"), model.peers)
        assertNull(model.caller)
        assertEquals(PeerPresence.PENDING, model.presence["22222222"])
    }

    @Test fun peerFallbackWorksBeforeTheRosterArrives() {
        val model = CallScreenModel.from(base.copy(phase = Phase.OUTGOING, peer = "22222222"))!!
        assertEquals(listOf("22222222"), model.peers)
        assertFalse(model.group)
        assertEquals(2, model.groupSize)
    }

    @Test fun presenceFollowsTheMediaRoom() {
        val model = CallScreenModel.from(base.copy(
            phase = Phase.CONNECTED, connectedAt = 500, members = listOf("11111111", "22222222", "33333333"),
            participants = listOf("11111111", "22222222"),
        ))!!
        assertEquals(PeerPresence.PRESENT, model.presence["22222222"])
        assertEquals(PeerPresence.PENDING, model.presence["33333333"])
        assertEquals(500, model.connectedAt)
    }

    @Test fun linkNoteExplainsWhyTheCallMayStall() {
        val call = base.copy(phase = Phase.CONNECTED, members = listOf("11111111", "22222222"))
        assertEquals(LinkNote.NONE, CallScreenModel.from(call)!!.note)
        assertEquals(LinkNote.RECONNECTING, CallScreenModel.from(call.copy(signalingLost = true, link = Link.CONNECTING, online = false))!!.note)
        assertEquals(LinkNote.NO_NETWORK, CallScreenModel.from(call.copy(link = Link.WAITING_NETWORK, online = false))!!.note)
        assertEquals(LinkNote.RECONNECTING, CallScreenModel.from(call.copy(signalingLost = true))!!.note)
    }

    @Test fun mutedAndSpeakerAreCarried() {
        val model = CallScreenModel.from(base.copy(phase = Phase.CONNECTED, muted = true, speaker = true, peer = "22222222"))!!
        assertTrue(model.muted); assertTrue(model.speaker)
    }
}
