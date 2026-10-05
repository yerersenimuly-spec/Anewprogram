package app.line.ui.chat

import app.line.core.MessageStatus
import app.line.ui.chat.ChatPreview.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPreviewTest {
    @Test fun textCollapsesWhitespaceAndNewlinesIntoOneLine() {
        assertEquals("a b c", ChatPreview.of("text", "  a\n\n b \t c  ").text)
    }

    @Test fun longTextIsCut() {
        assertEquals(160, ChatPreview.of("text", "x".repeat(1000)).text.length)
    }

    @Test fun mediaKindsMapFromTheStoredKind() {
        assertEquals(Kind.IMAGE, ChatPreview.of("image", "{}").kind)
        assertEquals(Kind.VOICE, ChatPreview.of("voice", "{}").kind)
        assertEquals(Kind.FILE, ChatPreview.of("file", "{}").kind)
        assertEquals(Kind.TEXT, ChatPreview.of("something-new", "hi").kind)
    }

    @Test fun photoCaptionComesFromTheDescriptor() {
        val preview = ChatPreview.of("image", """{"kind":"media","type":"image","caption":"Вид с   крыши\n"}""")
        assertEquals(Kind.IMAGE, preview.kind)
        assertEquals("Вид с крыши", preview.text)
    }

    @Test fun brokenDescriptorFallsBackToAnEmptyCaption() {
        assertEquals("", ChatPreview.of("voice", "not json").text)
    }

    @Test fun onlyOutgoingMessagesCarryAMarker() {
        assertNull(Delivery.marker(false, MessageStatus.RECEIVED))
        assertEquals(MessageStatus.READ, Delivery.marker(true, MessageStatus.READ))
    }

    @Test fun markersFollowTheStatusExactly() {
        assertEquals("clock", Delivery.iconName(MessageStatus.PENDING))
        assertEquals("check", Delivery.iconName(MessageStatus.SENT))
        assertEquals("check_double", Delivery.iconName(MessageStatus.DELIVERED))
        assertEquals("check_double", Delivery.iconName(MessageStatus.READ))
        assertEquals("alert", Delivery.iconName(MessageStatus.FAILED))
    }

    @Test fun onlyFailedOutgoingMessagesCanBeRetried() {
        assertTrue(Delivery.canRetry(true, MessageStatus.FAILED))
        assertFalse(Delivery.canRetry(true, MessageStatus.PENDING))
        assertFalse(Delivery.canRetry(false, MessageStatus.FAILED))
    }
}
