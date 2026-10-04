package app.line.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class AttachmentPayloadTest {
    private val id = "3f2c1b4a-5d6e-4f70-8a91-b2c3d4e5f601"
    private val blob = "7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d"

    private fun valid(): JSONObject = JSONObject()
        .put("kind", "media").put("id", id).put("v", 2).put("type", "image").put("blob", blob)
        .put("key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        .put("size", AttachmentCrypto.encryptedSize(1000)).put("psize", 1000).put("mime", "image/jpeg")
        .put("w", 800).put("h", 600)

    private fun assertRejected(change: (JSONObject) -> Unit) {
        val json = valid().also(change)
        try {
            AttachmentPayload.fromJson(json)
            fail("accepted $json")
        } catch (expected: InvalidAttachmentException) {
            assertNull(AttachmentPayload.fromJsonOrNull(json))
        }
    }

    @Test fun roundTripKeepsEveryField() {
        val payload = AttachmentPayload(
            AttachmentPayload.Type.VOICE, blob, ByteArray(32) { 7 }, AttachmentCrypto.encryptedSize(5000), 5000, "audio/mp4",
            "memo.m4a", null, null, 12_000, byteArrayOf(4, 50, 100), null, null,
        )
        assertEquals(payload, AttachmentPayload.fromJson(payload.toJson(id)))
        assertEquals("media:voice", payload.previewKey())
        assertEquals("voice", payload.displayKind())
        assertTrue(payload.toString().contains("<redacted>"))
    }

    @Test fun acceptsAMinimalPayloadAndIgnoresUnknownFields() {
        val parsed = AttachmentPayload.fromJson(valid().put("future", "x"))
        assertEquals(AttachmentPayload.Type.IMAGE, parsed.type)
        assertEquals(800, parsed.width)
        assertNull(parsed.name)
    }

    @Test fun rejectsEverythingThePeerMustNotSend() {
        assertRejected { it.put("kind", "chat") }
        assertRejected { it.put("v", 1) }
        assertRejected { it.put("v", 3) }
        assertRejected { it.put("id", "not-a-uuid") }
        assertRejected { it.put("blob", "../../x") }
        assertRejected { it.put("type", "video") }
        assertRejected { it.put("key", Base64.getEncoder().encodeToString(ByteArray(31))) }
        assertRejected { it.put("key", "***") }
        assertRejected { it.remove("key") }
        assertRejected { it.put("size", 0) }
        assertRejected { it.put("size", AttachmentPayload.MAX_BLOB_BYTES + 1) }
        assertRejected { it.put("psize", it.getLong("size") + 1) }
        assertRejected { it.put("size", 1000.5) }
        assertRejected { it.put("size", "1000") }
        assertRejected { it.put("mime", "IMAGE/JPEG") }
        assertRejected { it.put("mime", "image") }
        assertRejected { it.put("mime", "a/" + "b".repeat(100)) }
        assertRejected { it.remove("h") }
        assertRejected { it.put("w", 0) }
        assertRejected { it.put("h", AttachmentPayload.MAX_DIMENSION + 1) }
        assertRejected { it.put("dur", AttachmentPayload.MAX_DURATION_MS + 1) }
        assertRejected { it.put("dur", -1) }
        assertRejected { it.put("wave", Base64.getEncoder().encodeToString(ByteArray(129))) }
        assertRejected { it.put("thumb", Base64.getEncoder().encodeToString(ByteArray(AttachmentPayload.MAX_THUMB_BYTES + 1))) }
        assertRejected { it.put("caption", "я".repeat(AttachmentPayload.MAX_CAPTION_BYTES)) }
    }

    @Test fun waveformValuesAreClampedAndNamesAndCaptionsSanitised() {
        val parsed = AttachmentPayload.fromJson(
            valid().put("wave", Base64.getEncoder().encodeToString(byteArrayOf(-5, 50, 127)))
                .put("name", "../../etc/pass\u202Ewd\u0000.txt").put("caption", "a\u0007b\r\nc"),
        )
        assertTrue(parsed.waveform!!.all { it in 0..Waveform.PEAK })
        assertEquals("passwd.txt", parsed.name)
        assertEquals("ab\nc", parsed.caption)
    }

    @Test fun sanitizeNameKeepsTheExtensionWhenShortening() {
        assertEquals("passwd", AttachmentPayload.sanitizeName("/etc/passwd"))
        assertNull(AttachmentPayload.sanitizeName(".."))
        assertNull(AttachmentPayload.sanitizeName("   "))
        assertNull(AttachmentPayload.sanitizeName(null))
        val long = AttachmentPayload.sanitizeName("x".repeat(300) + ".pdf")!!
        assertEquals(AttachmentPayload.MAX_NAME_CHARS, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test fun normalizeMimeFallsBackToOctetStream() {
        assertEquals("image/jpeg", AttachmentPayload.normalizeMime("Image/JPEG; charset=binary"))
        assertEquals(AttachmentPayload.DEFAULT_MIME, AttachmentPayload.normalizeMime("nonsense"))
        assertEquals(AttachmentPayload.DEFAULT_MIME, AttachmentPayload.normalizeMime(null))
    }

    @Test fun toJsonRefusesWhatAPeerWouldRefuse() {
        val bad = AttachmentPayload(
            AttachmentPayload.Type.FILE, "nope", ByteArray(32), 100, 50, "text/plain", null, null, null, null, null, null, null,
        )
        try {
            bad.toJson(id)
            fail()
        } catch (expected: InvalidAttachmentException) {
            assertNotNull(expected.message)
        }
        assertFalse(bad.toString().isEmpty())
    }
}
