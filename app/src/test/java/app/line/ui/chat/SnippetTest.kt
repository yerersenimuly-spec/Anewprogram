package app.line.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetTest {
    @Test fun excerptCentersOnTheFirstHitAndMarksTrimmedEnds() {
        val text = "Встретимся завтра у входа в парк, возьми зонт и купи воды заранее, чтобы не стоять в очереди"
        val result = Snippet.around(text, "ЗОНТ", radius = 20)
        assertTrue(result.text.startsWith("…"))
        assertTrue(result.text.endsWith("…"))
        assertEquals("зонт", result.text.substring(result.matchStart, result.matchEnd))
    }

    @Test fun excerptWithoutAHitIsJustTheBeginning() {
        val result = Snippet.around("short text", "zzz")
        assertEquals("short text", result.text)
        assertEquals(-1, result.matchStart)
    }

    @Test fun whitespaceInTheSourceIsCollapsedBeforeMatching() {
        val result = Snippet.around("one\n\n  two   three", "two three")
        assertEquals("one two three", result.text)
        assertEquals("two three", result.text.substring(result.matchStart, result.matchEnd))
    }
}
