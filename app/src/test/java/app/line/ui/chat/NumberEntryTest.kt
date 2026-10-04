package app.line.ui.chat

import app.line.ui.chat.NumberEntry.Feedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberEntryTest {
    private val own = "12345678"

    @Test fun numbersAreShownInTwoGroupsOfFour() {
        assertEquals("1234 5678", NumberEntry.spaced("12345678"))
        assertEquals("1234 5", NumberEntry.spaced("12345"))
        assertEquals("1234", NumberEntry.spaced("1234"))
    }

    @Test fun digitsDropEverythingElseAndStopAtEight() {
        assertEquals("12345678", NumberEntry.digits("1234 5678 90"))
        assertEquals("1234", NumberEntry.digits("a1-2b3 4"))
    }

    @Test fun typingTheFifthDigitInsertsTheSpaceAndKeepsTheCaretAfterIt() {
        assertEquals("1234 5" to 6, NumberEntry.reformat("12345", 5))
        assertEquals("1234" to 4, NumberEntry.reformat("1234", 4))
        assertEquals("1234 5678" to 9, NumberEntry.reformat("12345678", 8))
    }

    @Test fun editingInTheMiddleKeepsTheCaretAtTheSameDigit() {
        assertEquals("1234 5678" to 2, NumberEntry.reformat("12 345678", 2))
        assertEquals("" to 0, NumberEntry.reformat("", 0))
    }

    @Test fun ownNumberIsRejectedWithItsOwnFeedback() {
        assertEquals(Feedback.OWN, NumberEntry.feedback("1234 5678", own))
        assertFalse(NumberEntry.isValid("12345678", own))
    }

    @Test fun badFormatIsRejectedAndEmptyIsSilent() {
        assertEquals(Feedback.FORMAT, NumberEntry.feedback("1234", own))
        assertEquals(Feedback.FORMAT, NumberEntry.feedback("1234 56ab", own))
        assertEquals(Feedback.NONE, NumberEntry.feedback("", own))
    }

    @Test fun anotherPersonsNumberIsValidWithOrWithoutSpaces() {
        assertTrue(NumberEntry.isValid("8765 4321", own))
        assertEquals(Feedback.NONE, NumberEntry.feedback("87654321", own))
    }
}
