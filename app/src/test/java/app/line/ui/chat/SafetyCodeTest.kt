package app.line.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyCodeTest {
    @Test fun sixtyDigitsBecomeFourLinesOfThreeGroups() {
        val code = (1..60).joinToString("") { (it % 10).toString() }
        val lines = SafetyCode.format(code).lines()
        assertEquals(4, lines.size)
        assertEquals(3, lines[0].split("  ").size)
        assertEquals("12345  67890  12345", lines[0])
    }

    @Test fun nonDigitsAreIgnored() {
        assertEquals("12345  67890", SafetyCode.format("12345 6789-0"))
    }
}
