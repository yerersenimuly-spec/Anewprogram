package app.line.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class AttachmentCryptoTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val chunk = AttachmentCrypto.CHUNK_SIZE
    private val sealedChunk = chunk + AttachmentCrypto.TAG_SIZE
    private val header = AttachmentCrypto.HEADER_SIZE

    private fun bytes(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
    private fun seal(plain: ByteArray, with: ByteArray = key) =
        ByteArrayOutputStream().also { AttachmentCrypto.encrypt(with, ByteArrayInputStream(plain), it) }.toByteArray()
    private fun open(blob: ByteArray, with: ByteArray = key) =
        ByteArrayOutputStream().also { AttachmentCrypto.decrypt(with, ByteArrayInputStream(blob), it) }.toByteArray()
    private fun assertCorrupt(blob: ByteArray, with: ByteArray = key) {
        try {
            open(blob, with)
            fail("opened a damaged blob")
        } catch (expected: AttachmentCorruptException) {
        }
    }

    @Test fun roundTripAtEveryChunkBoundary() {
        for (size in listOf(0, 1, 15, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 17)) {
            val plain = bytes(size)
            val blob = seal(plain)
            assertEquals("size $size", AttachmentCrypto.encryptedSize(size.toLong()), blob.size.toLong())
            assertEquals(size.toLong(), AttachmentCrypto.plainSizeOf(blob.size.toLong()))
            assertArrayEquals(plain, open(blob))
        }
    }

    @Test fun plainSizeOfRejectsLengthsNoBlobCanHave() {
        assertEquals(-1, AttachmentCrypto.plainSizeOf(0))
        assertEquals(-1, AttachmentCrypto.plainSizeOf(header.toLong()))
        assertEquals(-1, AttachmentCrypto.plainSizeOf(header + AttachmentCrypto.TAG_SIZE - 1L))
        assertEquals(-1, AttachmentCrypto.plainSizeOf(header + sealedChunk + AttachmentCrypto.TAG_SIZE.toLong()))
        assertEquals(16, AttachmentCrypto.plainSizeOf(header + 2L * AttachmentCrypto.TAG_SIZE))
        assertEquals(0, AttachmentCrypto.plainSizeOf(AttachmentCrypto.encryptedSize(0)))
    }

    @Test fun everySealingUsesAFreshNonce() {
        val plain = bytes(100)
        assertNotEquals(seal(plain).toList(), seal(plain).toList())
    }

    @Test fun flippedBitsAnywhereAreDetected() {
        val blob = seal(bytes(2 * chunk + 5))
        for (position in listOf(0, 3, header, blob.size / 2, sealedChunk + 100, blob.size - 1)) {
            assertCorrupt(blob.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() })
        }
    }

    @Test fun truncationExtensionAndReorderingAreDetected() {
        val blob = seal(bytes(3 * chunk))
        assertCorrupt(blob.copyOf(blob.size - 1))
        assertCorrupt(blob.copyOf(header + 2 * sealedChunk))
        assertCorrupt(blob.copyOf(header))
        assertCorrupt(blob + ByteArray(20))
        val first = blob.copyOfRange(header, header + sealedChunk)
        val second = blob.copyOfRange(header + sealedChunk, header + 2 * sealedChunk)
        val swapped = blob.copyOf().also {
            second.copyInto(it, header)
            first.copyInto(it, header + sealedChunk)
        }
        assertCorrupt(swapped)
    }

    @Test fun aWrongKeyAndAnUnknownVersionAreRejected() {
        val blob = seal(bytes(100))
        assertCorrupt(blob, ByteArray(32))
        assertCorrupt(blob.copyOf().also { it[0] = 2 })
        assertCorrupt(ByteArray(0))
    }

    @Test fun anEmptyFileIsDistinguishableFromAMissingFinalChunk() {
        val empty = seal(ByteArray(0))
        assertEquals(24, empty.size)
        assertArrayEquals(ByteArray(0), open(empty))
        assertCorrupt(seal(bytes(chunk + 1)).copyOf(header + sealedChunk))
    }

    @Test fun openStreamPlaysFromAFileAndFailsOnACorruptTail() {
        val plain = bytes(chunk + 1000)
        val file = folder.newFile().also { it.writeBytes(seal(plain)) }
        assertArrayEquals(plain, AttachmentCrypto.openStream(key, file).use { it.readBytes() })

        val blob = seal(plain)
        blob[blob.size - 3] = (blob[blob.size - 3].toInt() xor 1).toByte()
        val damaged = folder.newFile().also { it.writeBytes(blob) }
        try {
            AttachmentCrypto.openStream(key, damaged).use { it.readBytes() }
            fail()
        } catch (expected: AttachmentCorruptException) {
        }
    }
}
