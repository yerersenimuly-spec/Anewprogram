package app.line.ui.calls

import app.line.CallState
import app.line.Phase
import app.line.core.NumberInput
import org.junit.Assert.*
import org.junit.Test

class DialBufferTest {
    private fun type(text: String, start: DialBuffer = DialBuffer()): DialBuffer = text.fold(start) { b, c -> b.digit(c) }

    @Test fun typingFormatsAndStopsAtEightDigits() {
        assertEquals("", DialBuffer().display())
        assertTrue(DialBuffer().isEmpty)
        assertEquals("1234 5", type("12345").display())
        assertEquals("1234 5678", type("123456789").display())
        assertEquals(listOf("12345678"), type("123456789").numbers())
    }

    @Test fun nonDigitsAreIgnored() {
        assertEquals("12", type("1a2-").display())
    }

    @Test fun participantCanBeAddedOnlyAfterAFullNumberAndWithinTheLimit() {
        assertFalse(type("1234567").canAdd(3))
        val full = type("12345678")
        assertTrue(full.canAdd(3))
        val two = type("87654321", full.add(3))
        assertEquals(listOf("12345678", "87654321"), two.numbers())
        assertEquals("1234 5678, 8765 4321", two.display())
        assertTrue(two.canAdd(3))
        val three = type("11112222", two.add(3))
        assertFalse(three.canAdd(3))
        assertEquals(three, three.add(3))
    }

    @Test fun pendingSeparatorIsShownAndBackspaceRemovesItFirst() {
        val pending = type("12345678").add(7)
        assertEquals("1234 5678,", pending.display())
        assertEquals(listOf("12345678"), pending.numbers())
        val back = pending.backspace()
        assertEquals("1234 5678", back.display())
        assertEquals("1234 567", back.backspace().display())
        assertTrue(DialBuffer().backspace().isEmpty)
        assertTrue(pending.clear().isEmpty)
    }

    @Test fun ownNumberAndRepeatsAreFlaggedAsSoonAsTheyAreComplete() {
        assertNull(type("1234567").liveProblem("12345678"))
        assertEquals(NumberInput.Problem.OWN, type("12345678").liveProblem("12345678"))
        val repeated = type("12345678", type("12345678").add(7))
        assertEquals(NumberInput.Problem.DUPLICATE, repeated.liveProblem("00000000"))
        assertNull(type("12345678").liveProblem(""))
    }

    @Test fun validationUsesTheSharedNumberRules() {
        assertEquals(NumberInput.Result.Invalid(NumberInput.Problem.EMPTY), DialBuffer().validate("11111111", 7))
        assertEquals(NumberInput.Result.Invalid(NumberInput.Problem.FORMAT), type("1234567").validate("11111111", 7))
        assertEquals(NumberInput.Result.Invalid(NumberInput.Problem.OWN), type("11111111").validate("11111111", 7))
        assertEquals(NumberInput.Result.Valid(listOf("22222222")), type("22222222").validate("11111111", 7))
        val two = type("33333333", type("22222222").add(7))
        assertEquals(NumberInput.Result.Invalid(NumberInput.Problem.TOO_MANY), two.validate("11111111", 1))
    }

    @Test fun pastedTextYieldsNumbersWithinTheLimit() {
        assertEquals(listOf("12345678"), DialBuffer.fromText("1234 5678", 3).numbers())
        assertEquals(listOf("12345678", "87654321"), DialBuffer.fromText("Мой номер 12345678, второй: 8765-4321", 3).numbers())
        assertEquals(listOf("12345678"), DialBuffer.fromText("12345678 12345678", 3).numbers())
        assertEquals(listOf("12345678", "87654321"), DialBuffer.fromText("12345678 87654321 11112222", 2).numbers())
        assertEquals("123", DialBuffer.fromText("tel 123", 3).display())
        assertTrue(DialBuffer.fromText("hello", 3).isEmpty)
    }

    @Test fun availabilityExplainsWhyCallingIsOff() {
        val ready = CallState(configReady = true, online = true, mediaReady = true, number = "11111111")
        assertEquals(DialBlock.NONE, DialAvailability.of(ready))
        assertEquals(DialBlock.NOT_CONFIGURED, DialAvailability.of(ready.copy(configReady = false)))
        assertEquals(DialBlock.IN_CALL, DialAvailability.of(ready.copy(phase = Phase.CONNECTED)))
        assertEquals(DialBlock.CALLS_DISABLED, DialAvailability.of(ready.copy(callsEnabled = false)))
        assertEquals(DialBlock.MEDIA_UNAVAILABLE, DialAvailability.of(ready.copy(mediaReady = false)))
        assertEquals(DialBlock.NONE, DialAvailability.of(ready.copy(online = false, mediaReady = false)))
        assertEquals(7, DialAvailability.maxPeers(ready.copy(maxParticipants = 8)))
        assertEquals(1, DialAvailability.maxPeers(ready.copy(maxParticipants = 2)))
    }
}
