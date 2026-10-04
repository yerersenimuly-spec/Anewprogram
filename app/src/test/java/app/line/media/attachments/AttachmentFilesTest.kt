package app.line.media.attachments

import app.line.core.AttachmentCorruptException
import app.line.core.AttachmentCrypto
import app.line.core.AttachmentTooLarge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

class AttachmentFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = System.currentTimeMillis()
    private lateinit var files: AttachmentFiles
    private val blobId = "5c9e0a1b-2d3f-4a5b-8c7d-6e5f4a3b2c1d"
    private val messageId = "9b8a7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private val key = ByteArray(32) { it.toByte() }
    private val plain = ByteArray(200_000).also { Random(5).nextBytes(it) }

    @Before fun setUp() {
        files = AttachmentFiles(folder.newFolder("files"), folder.newFolder("cache")) { now }
    }

    private fun seal() = files.seal(blobId, key, ByteArrayInputStream(plain))

    @Test fun sealWritesOneEncryptedFileAndNoTemporaryLeftover() {
        assertEquals(plain.size.toLong(), seal())
        val blob = files.blob(blobId)
        assertEquals(AttachmentCrypto.encryptedSize(plain.size.toLong()), blob.length())
        assertEquals(listOf("$blobId.enc"), files.blobDir.list()!!.toList())
        val latin = Charsets.ISO_8859_1
        assertFalse(String(blob.readBytes(), latin).contains(String(plain.copyOf(64), latin)))
        files.verify(key, blob, plain.size.toLong())
    }

    @Test fun sealStopsAtTheLimitAndLeavesNothingBehind() {
        try {
            files.seal(blobId, key, ByteArrayInputStream(plain), maxPlain = 1_000)
            fail()
        } catch (expected: AttachmentTooLarge) {
            assertEquals(1_000L, expected.limit)
        }
        assertTrue(files.blobDir.list().orEmpty().isEmpty())
    }

    @Test fun verifyRejectsDamageAWrongSizeAndAWrongKey() {
        seal()
        val blob = files.blob(blobId)
        files.verify(key, blob, plain.size.toLong())
        expectCorrupt { files.verify(key, blob, plain.size - 1L) }
        expectCorrupt { files.verify(ByteArray(32), blob, plain.size.toLong()) }
        val bytes = blob.readBytes().also { it[1000] = (it[1000].toInt() xor 1).toByte() }
        blob.writeBytes(bytes)
        expectCorrupt { files.verify(key, blob, plain.size.toLong()) }
    }

    @Test fun plainCopyIsReusedAndNeverLeavesAPartialFile() {
        seal()
        val copy = files.plainCopy(messageId, "doc.pdf", files.blob(blobId), key, plain.size.toLong())
        assertArrayEquals(plain, copy.readBytes())
        assertEquals(File(files.sharedDir, messageId), copy.parentFile)
        now += 5_000
        assertEquals(copy, files.plainCopy(messageId, "doc.pdf", files.blob(blobId), key, plain.size.toLong()))
        assertEquals(now, copy.lastModified())

        val other = "1b8a7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
        expectCorrupt { files.plainCopy(other, "doc.pdf", files.blob(blobId), ByteArray(32), plain.size.toLong()) }
        assertTrue(File(files.sharedDir, other).list().orEmpty().isEmpty())
    }

    @Test fun deletingAMessageRemovesCiphertextPartialDownloadAndPlaintext() {
        seal()
        files.plainCopy(messageId, "doc.pdf", files.blob(blobId), key, plain.size.toLong())
        File(files.blob(blobId).path + BlobTransfer.PART_SUFFIX).writeBytes(byteArrayOf(1))
        files.deleteMessage(messageId, blobId)
        assertTrue(files.blobDir.list().orEmpty().isEmpty())
        assertFalse(File(files.sharedDir, messageId).exists())
        files.deleteMessage(messageId, blobId)
    }

    @Test fun decryptedCopiesExpireAfterTheirAgeButRecentOnesStay() {
        seal()
        val old = files.plainCopy(messageId, "old.bin", files.blob(blobId), key, plain.size.toLong())
        now += 25 * 60 * 60 * 1000L
        val recentId = "2b8a7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
        files.plainCopy(recentId, "new.bin", files.blob(blobId), key, plain.size.toLong())
        val recent = files.plainCopy(recentId, "new.bin", files.blob(blobId), key, plain.size.toLong())
        assertEquals(1, files.sweepShared(24 * 60 * 60 * 1000L))
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test fun orphanBlobsGoButReferencedAndFreshOnesStay() {
        seal()
        val referenced = "3c9e0a1b-2d3f-4a5b-8c7d-6e5f4a3b2c1d"
        files.seal(referenced, key, ByteArrayInputStream(ByteArray(10)))
        File(files.blob(referenced).path + BlobTransfer.PART_SUFFIX).writeBytes(byteArrayOf(1))
        val tenMinutes = 10 * 60 * 1000L
        assertEquals(0, files.sweepOrphans(setOf(referenced), tenMinutes))
        now += tenMinutes + 1
        files.blobDir.listFiles()!!.forEach { it.setLastModified(now - tenMinutes - 1) }
        assertEquals(1, files.sweepOrphans(setOf(referenced), tenMinutes))
        assertTrue(files.blob(referenced).exists())
        assertTrue(File(files.blob(referenced).path + BlobTransfer.PART_SUFFIX).exists())
        assertFalse(files.blob(blobId).exists())
    }

    @Test fun pathsAreBuiltFromUuidsOnly() {
        for (bad in listOf("../x", "a/b", "", "not-a-uuid")) {
            try {
                files.blob(bad)
                fail(bad)
            } catch (expected: IllegalArgumentException) {
            }
            try {
                files.plainCopy(bad, "n", File("x"), key, 1)
                fail(bad)
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertEquals("$blobId.enc", files.blob(blobId.uppercase()).name)
    }

    private fun expectCorrupt(block: () -> Unit) {
        try {
            block()
            fail("accepted damaged data")
        } catch (expected: AttachmentCorruptException) {
        }
    }
}
