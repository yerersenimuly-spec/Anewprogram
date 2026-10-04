package app.line.ui.calls

import org.junit.Assert.*
import org.junit.Test

class CallFormatTest {
    @Test fun numbersAreGroupedInFours() {
        assertEquals("1234 5678", CallFormat.number("12345678"))
        assertEquals("1234 5", CallFormat.number("12345"))
        assertEquals("123", CallFormat.number("123"))
        assertEquals("", CallFormat.number(""))
        assertEquals("1234 5678, 8765 4321", CallFormat.numbers(listOf("12345678", "87654321")))
    }

    @Test fun durationIsTabularAndLocaleFree() {
        assertEquals("00:00", CallFormat.duration(0))
        assertEquals("00:09", CallFormat.duration(9))
        assertEquals("01:01", CallFormat.duration(61))
        assertEquals("59:59", CallFormat.duration(3599))
        assertEquals("1:00:00", CallFormat.duration(3600))
        assertEquals("2:05:07", CallFormat.duration(7507))
        assertEquals("00:00", CallFormat.duration(-5))
    }

    @Test fun elapsedIgnoresUnsetAndBackwardsClocks() {
        assertEquals(0, CallFormat.elapsedSeconds(0, 50_000))
        assertEquals(5, CallFormat.elapsedSeconds(10_000, 15_999))
        assertEquals(0, CallFormat.elapsedSeconds(20_000, 10_000))
    }

    @Test fun peersExcludeTheUserAndFallBackToTheCallPeer() {
        assertEquals(listOf("22222222", "33333333"), CallFormat.peers(listOf("11111111", "22222222", "33333333"), "11111111", ""))
        assertEquals(listOf("22222222", "33333333"), CallFormat.peers(emptyList(), "11111111", "22222222, 33333333"))
        assertEquals(emptyList<String>(), CallFormat.peers(emptyList(), "11111111", ""))
    }

    @Test fun peerLabelTellsNameFromNumber() {
        val names = mapOf("22222222" to "Ернур")
        val lookup = { n: String -> names[n] ?: CallFormat.number(n) }
        assertEquals(PeerLabel("Ернур", true), PeerLabels.resolve("22222222", lookup))
        assertEquals(PeerLabel("3333 3333", false), PeerLabels.resolve("33333333", lookup))
        assertEquals("Ернур, 3333 3333", PeerLabels.joined(listOf("22222222", "33333333"), lookup))
    }
}
