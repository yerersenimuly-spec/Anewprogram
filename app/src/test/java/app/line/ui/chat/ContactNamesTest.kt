package app.line.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNamesTest {
    @Test fun collapsesWhitespaceAndTrims() {
        assertEquals("Ернур Алиев", ContactNames.clean("  Ернур \u00A0 Алиев\n"))
    }

    @Test fun dropsControlAndDirectionalCharacters() {
        assertEquals("Ана", ContactNames.clean("А\u0000н\u202Eа"))
    }

    @Test fun keepsAtMostFortyCodePointsWithoutSplittingEmoji() {
        val result = ContactNames.clean("😀".repeat(60))
        assertEquals(40, result.codePointCount(0, result.length))
    }

    @Test fun blankInputClears() {
        assertEquals("", ContactNames.clean(" \t "))
    }

    @Test fun keyMatchesTheOneUsedBy071() {
        assertEquals("contact-12345678", ContactNames.key("12345678"))
    }
}
