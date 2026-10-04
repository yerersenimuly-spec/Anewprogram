package app.line.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaDraftTest {
    private val blob = "7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d"
    private val id = "3f2c1b4a-5d6e-4f70-8a91-b2c3d4e5f601"
    private val key = ByteArray(32)

    @Test fun imagePayloadIsValidAndCarriesSizeAndThumb() {
        val payload = MediaDraft.image(1024, 768, ByteArray(500), "  hi  ").toPayload(blob, key, 120_000)
        assertEquals(AttachmentCrypto.encryptedSize(120_000), payload.size)
        assertEquals(1024, payload.width)
        assertEquals(500, payload.thumb!!.size)
        assertEquals("hi", payload.caption)
        assertEquals(payload, AttachmentPayload.fromJson(payload.toJson(id)))
    }

    @Test fun anOversizedThumbOrBadDimensionsAreDroppedNotSent() {
        val payload = MediaDraft.image(0, 768, ByteArray(AttachmentPayload.MAX_THUMB_BYTES + 1), null).toPayload(blob, key, 10)
        assertNull(payload.thumb)
        assertNull(payload.width)
        assertNull(payload.height)
        payload.toJson(id)
    }

    @Test fun aLongCaptionIsCutOnACharacterBoundary() {
        val caption = MediaDraft.caption("😀".repeat(500))!!
        assertTrue(caption.toByteArray().size <= AttachmentPayload.MAX_CAPTION_BYTES)
        assertEquals(caption.codePointCount(0, caption.length) * 4, caption.toByteArray().size)
        assertNull(MediaDraft.caption("   \u0007 "))
        MediaDraft.image(10, 10, null, "я".repeat(5000)).toPayload(blob, key, 10).toJson(id)
    }

    @Test fun voiceKeepsDurationWithinTheWireLimitAndBuildsTheWaveform() {
        val payload = MediaDraft.voice(AttachmentPayload.MAX_DURATION_MS * 2, intArrayOf(100, 20_000, 3_000), "audio/mp4").toPayload(blob, key, 5_000)
        assertEquals(AttachmentPayload.MAX_DURATION_MS, payload.durationMs)
        assertEquals(Waveform.DEFAULT_BARS, payload.waveform!!.size)
        assertEquals("media:voice", payload.previewKey())
        payload.toJson(id)
    }

    @Test fun fileNamesAndMimeTypesAreSanitised() {
        val payload = MediaDraft.file("../x/a\u202Eb.pdf", "application/pdf").toPayload(blob, key, 1)
        assertEquals("ab.pdf", payload.name)
        assertEquals(AttachmentPayload.DEFAULT_MIME, MediaDraft.file(null, "weird").toPayload(blob, key, 1).mime)
    }

    @Test fun theLimitIsEnforcedAndFitsTheServer() {
        MediaDraft.file("a", "text/plain").toPayload(blob, key, MediaLimits.MAX_FILE_BYTES)
        try {
            MediaDraft.file("a", "text/plain").toPayload(blob, key, MediaLimits.MAX_FILE_BYTES + 1)
            fail()
        } catch (expected: AttachmentTooLarge) {
            assertEquals(MediaLimits.MAX_FILE_BYTES, expected.limit)
        }
        assertTrue(AttachmentCrypto.encryptedSize(MediaLimits.MAX_FILE_BYTES) < 26L * 1024 * 1024)
        assertTrue(AttachmentCrypto.encryptedSize(MediaLimits.MAX_FILE_BYTES) <= AttachmentPayload.MAX_BLOB_BYTES)
    }
}
