package app.line.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8LimitTest {
    @Test fun countsBytesPerScript() {
        assertEquals(5, Utf8Limit.bytes("hello"))
        assertEquals(12, Utf8Limit.bytes("привет"))
        assertEquals(3, Utf8Limit.bytes("€"))
        assertEquals(4, Utf8Limit.bytes("😀"))
    }

    @Test fun fitStopsBeforeTheCharacterThatDoesNotFit() {
        assertEquals(2, Utf8Limit.fit("привет", 0, 6, 5))
        assertEquals(3, Utf8Limit.fit("abc", 0, 3, 100))
        assertEquals(0, Utf8Limit.fit("abc", 0, 3, 0))
    }

    @Test fun fitNeverSplitsASurrogatePair() {
        val text = "a😀b"
        assertEquals(1, Utf8Limit.fit(text, 0, text.length, 4))
        assertEquals(3, Utf8Limit.fit(text, 0, text.length, 5))
    }

    @Test fun rangeIsRespected() {
        assertEquals(4, Utf8Limit.bytes("привет", 2, 4))
    }
}
