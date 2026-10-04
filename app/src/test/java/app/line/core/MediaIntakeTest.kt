package app.line.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class MediaIntakeTest {
    private val id = "3f2c1b4a-5d6e-4f70-8a91-b2c3d4e5f601"
    private val blob = "7A8B9C0D-1E2F-4A3B-9C4D-5E6F7A8B9C0E"

    private fun json(plain: Long = 1000): JSONObject = JSONObject()
        .put("kind", "media").put("id", id).put("v", 2).put("type", "file").put("blob", blob)
        .put("key", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
        .put("size", AttachmentCrypto.encryptedSize(plain)).put("psize", plain).put("mime", "application/pdf").put("name", "a.pdf")

    @Test fun acceptsAValidPayloadWithACanonicalLowerCaseBlobId() {
        val accepted = MediaIntake.classify(json(), id) as MediaIntake.Accepted
        assertEquals(blob.lowercase(), accepted.payload.blobId)
        assertEquals(blob.lowercase(), JSONObject(accepted.descriptor).getString("blob"))
        assertEquals(accepted.payload, MediaIntake.descriptor(accepted.descriptor))
    }

    @Test fun everythingElseBecomesAPlaceholderInsteadOfAnException() {
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(json().put("v", 3), id))
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(json().put("type", "sticker"), id))
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(json().put("id", "00000000-0000-4000-8000-000000000000"), id))
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(json().put("size", 5000), id))
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(json().put("size", 1000.0), id))
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(JSONObject().put("kind", "media"), id))
        assertEquals(MediaIntake.Unsupported, MediaIntake.classify(json().put("key", JSONObject()), id))
    }

    @Test fun descriptorOfGarbageIsNull() {
        assertNull(MediaIntake.descriptor("not json"))
        assertNull(MediaIntake.descriptor("{}"))
        assertNull(MediaIntake.descriptor(""))
    }

    @Test fun previewKeysAreLanguageNeutral() {
        val text = (MediaIntake.classify(json(), id) as MediaIntake.Accepted).descriptor
        assertEquals("media:file", MediaPreview.key("file", text))
        assertEquals("media:voice", MediaPreview.key("voice", "broken"))
        assertNull(MediaPreview.key("text", "hello"))
        assertNull(MediaPreview.caption(text))
    }
}
