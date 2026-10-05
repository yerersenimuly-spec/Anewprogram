package app.line.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFetchPolicyTest {
    private val mib = 1024L * 1024
    private fun fetch(type: AttachmentPayload.Type, size: Long, open: Boolean, metered: Boolean) =
        AutoFetchPolicy.shouldFetch(type, size, AutoFetchPolicy.Context(open, metered))

    @Test fun filesAreNeverFetchedAutomatically() {
        assertFalse(fetch(AttachmentPayload.Type.FILE, 1, open = true, metered = false))
    }

    @Test fun anOpenChatOnWifiTakesPhotosAndVoiceUpToFourMebibytes() {
        assertTrue(fetch(AttachmentPayload.Type.IMAGE, 4 * mib, open = true, metered = false))
        assertFalse(fetch(AttachmentPayload.Type.IMAGE, 4 * mib + 1, open = true, metered = false))
        assertTrue(fetch(AttachmentPayload.Type.VOICE, 2 * mib, open = true, metered = false))
    }

    @Test fun meteredNetworksAndClosedChatsOnlyTakeSmallItems() {
        assertTrue(fetch(AttachmentPayload.Type.IMAGE, mib, open = true, metered = true))
        assertFalse(fetch(AttachmentPayload.Type.IMAGE, mib + 1, open = true, metered = true))
        assertTrue(fetch(AttachmentPayload.Type.VOICE, mib, open = false, metered = false))
        assertFalse(fetch(AttachmentPayload.Type.VOICE, mib + 1, open = false, metered = false))
        assertFalse(fetch(AttachmentPayload.Type.VOICE, 1, open = false, metered = true))
    }
}

class SafeFileNameTest {
    private fun payload(type: AttachmentPayload.Type, name: String?, mime: String) = AttachmentPayload(
        type, "7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d", ByteArray(32), AttachmentCrypto.encryptedSize(1), 1, mime, name, null, null, null, null, null, null,
    )

    @Test fun neverContainsSeparatorsOrLeadingDots() {
        assertEquals("a_b_c.txt", SafeFileName.clean("a/b\\c.txt"))
        assertEquals("hidden", SafeFileName.clean("...hidden"))
        assertEquals("a_b.txt", SafeFileName.clean("a:b.txt"))
        assertEquals("a_.txt", SafeFileName.clean("a\u0000.txt"))
        assertNull(SafeFileName.clean("..."))
        assertNull(SafeFileName.clean("///"))
        assertNull(SafeFileName.clean(null))
    }

    @Test fun longNamesKeepTheirExtension() {
        val name = SafeFileName.clean("n".repeat(400) + ".JPEG")!!
        assertTrue(name.length <= 100)
        assertTrue(name.endsWith(".jpeg"))
    }

    @Test fun unnamedAttachmentsGetATypeNameAndExtension() {
        assertEquals("photo.jpg", SafeFileName.forPayload(payload(AttachmentPayload.Type.IMAGE, null, "image/jpeg")))
        assertEquals("voice.m4a", SafeFileName.forPayload(payload(AttachmentPayload.Type.VOICE, null, "audio/mp4")))
        assertEquals("file", SafeFileName.forPayload(payload(AttachmentPayload.Type.FILE, null, "application/octet-stream")))
        assertEquals("отчёт.pdf", SafeFileName.forPayload(payload(AttachmentPayload.Type.FILE, "отчёт.pdf", "application/pdf")))
        assertEquals("notes.txt", SafeFileName.forPayload(payload(AttachmentPayload.Type.FILE, "notes", "text/plain")))
    }
}

class ProgressThrottleTest {
    @Test fun letsOneUpdateThroughPerInterval() {
        var now = 1_000L
        val throttle = ProgressThrottle(100) { now }
        assertTrue(throttle.tryAcquire())
        now += 99
        assertFalse(throttle.tryAcquire())
        now += 1
        assertTrue(throttle.tryAcquire())
        throttle.reset()
        assertTrue(throttle.tryAcquire())
    }

    @Test fun theFirstUpdateAtTimeZeroPasses() {
        assertTrue(ProgressThrottle(100) { 0L }.tryAcquire())
    }
}
