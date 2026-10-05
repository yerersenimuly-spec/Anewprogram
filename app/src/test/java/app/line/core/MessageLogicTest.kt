package app.line.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageStatusTest {
    @Test fun outgoingStatusNeverMovesBackwards() {
        val order = listOf(MessageStatus.PENDING, MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ)
        for (a in order) for (b in order) {
            assertEquals("$a + $b", if (order.indexOf(b) > order.indexOf(a)) b else a, MessageStatus.advance(a, b))
        }
    }

    @Test fun onlyAPendingMessageCanFailAndAFailedOneResumesForward() {
        assertEquals(MessageStatus.FAILED, MessageStatus.advance(MessageStatus.PENDING, MessageStatus.FAILED))
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.SENT, MessageStatus.FAILED))
        assertEquals(MessageStatus.SENT, MessageStatus.advance(MessageStatus.FAILED, MessageStatus.SENT))
        assertEquals(MessageStatus.RECEIVED, MessageStatus.advance(MessageStatus.RECEIVED, MessageStatus.READ))
        assertTrue(MessageStatus.entries.all { MessageStatus.advance(it, it) == it })
    }

    @Test fun legacyQueuedMeansSentAndUnknownMeansPending() {
        assertEquals(MessageStatus.SENT, MessageStatus.parse("queued"))
        assertEquals(MessageStatus.PENDING, MessageStatus.parse("???"))
        assertEquals(MessageStatus.PENDING, MessageStatus.parse(null))
    }
}

class ReceiptsTest {
    private val id = "3f2c1b4a-5d6e-4f70-8a91-b2c3d4e5f601"

    @Test fun readReceiptsValidateTheirIds() {
        assertEquals(listOf(id), Receipts.parseRead(Receipts.read(listOf(id))))
        assertNull(Receipts.parseRead(Receipts.read(emptyList())))
        assertNull(Receipts.parseRead(Receipts.read(listOf("x"))))
        assertNull(Receipts.parseRead(Receipts.read(List(Receipts.MAX_IDS + 1) { id })))
        assertNull(Receipts.parseRead(JSONObject().put("kind", "chat")))
    }

    @Test fun peersAreSupportedFromVersionTwo() {
        assertTrue(Receipts.supported(Receipts.chat(id, "hi")))
        assertFalse(Receipts.supported(JSONObject().put("kind", "chat").put("v", 1)))
        assertFalse(Receipts.supported(JSONObject().put("kind", "chat")))
    }
}

class WaveformTest {
    @Test fun silenceIsAFlatLineAndBarsAreBounded() {
        val flat = Waveform.fromAmplitudes(IntArray(100))
        assertEquals(Waveform.DEFAULT_BARS, flat.size)
        assertTrue(flat.all { it.toInt() == Waveform.FLOOR })
        assertEquals(Waveform.DEFAULT_BARS, Waveform.fromAmplitudes(IntArray(0)).size)
        assertEquals(Waveform.DEFAULT_BARS, Waveform.fromAmplitudes(intArrayOf(5)).size)
    }

    @Test fun loudInputReachesThePeakAndQuietInputIsNotStretched() {
        assertEquals(Waveform.PEAK, Waveform.fromAmplitudes(IntArray(96) { 32767 }).max().toInt())
        assertTrue(Waveform.fromAmplitudes(IntArray(96) { 600 }).max() < 60)
        assertTrue(Waveform.fromAmplitudes(IntArray(96) { 99_999 }).all { it in 0..Waveform.PEAK })
    }

    @Test fun base64DecodingClampsUntrustedBars() {
        assertTrue(Waveform.fromBase64(Waveform.toBase64(byteArrayOf(-1, 50, 120))).all { it in 0..Waveform.PEAK })
        try {
            Waveform.fromBase64("A".repeat(400))
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }
}
