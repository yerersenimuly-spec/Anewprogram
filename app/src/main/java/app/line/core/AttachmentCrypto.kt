package app.line.core

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The blob is not an authentic attachment: modified, truncated, reordered, sealed with another key, or an unknown version. */
class AttachmentCorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming AEAD for attachment blobs: the STREAM construction over AES-256-GCM.
 *
 * Layout: header (version byte 1 + 7 random nonce-prefix bytes), then chunks of up to 64 KiB of plaintext, each sealed
 * as ciphertext || 16-byte tag. Chunk nonce = prefix || counter (u32, big endian) || last flag (1 on the final chunk
 * only); the AAD is the header. The final chunk is empty only for an empty file. A chunk is verified before any of its
 * plaintext is released, so a flipped bit, a missing or extra chunk, reordering and a wrong key all fail with
 * [AttachmentCorruptException] (the [javax.crypto.AEADBadTagException] is its cause). Use a fresh key per blob.
 * The functions never close the streams they are given.
 */
object AttachmentCrypto {
    const val KEY_SIZE = 32
    const val CHUNK_SIZE = 64 * 1024
    const val HEADER_SIZE = 8
    const val TAG_SIZE = 16
    private const val VERSION = 1
    private const val PREFIX_SIZE = 7
    private const val NONCE_SIZE = 12
    private const val SEALED_SIZE = CHUNK_SIZE + TAG_SIZE
    private const val MAX_COUNTER = 0xFFFF_FFFFL
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_SIZE).also(random::nextBytes)

    /** Blob length for [plainSize] bytes of plaintext; depends on the size only. */
    fun encryptedSize(plainSize: Long): Long {
        require(plainSize >= 0) { "plainSize must not be negative" }
        val chunks = maxOf(1L, plainSize / CHUNK_SIZE + if (plainSize % CHUNK_SIZE == 0L) 0 else 1)
        return Math.addExact(Math.addExact(HEADER_SIZE.toLong(), plainSize), Math.multiplyExact(chunks, TAG_SIZE.toLong()))
    }

    /** Inverse of [encryptedSize]; -1 when no plaintext length produces a blob of [encryptedSize] bytes. */
    fun plainSizeOf(encryptedSize: Long): Long {
        val body = encryptedSize - HEADER_SIZE
        if (body < TAG_SIZE) return -1
        val chunks = (body + SEALED_SIZE - 1) / SEALED_SIZE
        val lastChunk = body - (chunks - 1) * SEALED_SIZE
        val emptyFile = chunks == 1L && lastChunk == TAG_SIZE.toLong()
        if (lastChunk <= TAG_SIZE && !emptyFile) return -1
        return body - chunks * TAG_SIZE
    }

    /** Seals [input] into [output]; returns the number of plaintext bytes read. */
    fun encrypt(key: ByteArray, input: InputStream, output: OutputStream, random: SecureRandom = SecureRandom()): Long {
        val secret = secretKey(key)
        val header = ByteArray(HEADER_SIZE)
        header[0] = VERSION.toByte()
        ByteArray(PREFIX_SIZE).also(random::nextBytes).copyInto(header, 1)
        output.write(header)

        val cipher = newCipher()
        val sealed = ByteArray(SEALED_SIZE)
        var current = ByteArray(CHUNK_SIZE)
        var ahead = ByteArray(CHUNK_SIZE)
        var length = readFully(input, current)
        var counter = 0L
        var total = 0L
        while (true) {
            // A full chunk is final only when nothing follows it, so one chunk is read ahead.
            val aheadLength = if (length == CHUNK_SIZE) readFully(input, ahead) else 0
            val last = aheadLength == 0
            val sealedLength = try {
                cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_SIZE * 8, nonce(header, counter, last)))
                cipher.updateAAD(header)
                cipher.doFinal(current, 0, length, sealed, 0)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("AES-GCM is not available", e)
            }
            output.write(sealed, 0, sealedLength)
            total += length
            if (last) break
            if (counter == MAX_COUNTER) throw IOException("Attachment has too many chunks")
            counter++
            val spare = current
            current = ahead
            ahead = spare
            length = aheadLength
        }
        output.flush()
        return total
    }

    /** Verifies and opens a blob from [input] into [output]; returns the number of plaintext bytes written. */
    fun decrypt(key: ByteArray, input: InputStream, output: OutputStream): Long {
        val reader = ChunkReader(key, input)
        var total = 0L
        while (true) {
            val length = reader.next()
            if (length < 0) break
            output.write(reader.plain, 0, length)
            total += length
        }
        output.flush()
        return total
    }

    /**
     * Opens a ciphertext [file] as a plaintext stream for playback or viewing. The header is checked here; each chunk
     * is authenticated as it is reached, so corruption (including a missing tail) surfaces as
     * [AttachmentCorruptException] from `read`. Sequential access only; closing the stream closes the file.
     */
    fun openStream(key: ByteArray, file: File): InputStream {
        val source = FileInputStream(file)
        try {
            return PlainStream(ChunkReader(key, source), source)
        } catch (e: Throwable) {
            source.close()
            throw e
        }
    }

    private fun secretKey(key: ByteArray): SecretKey {
        require(key.size == KEY_SIZE) { "Attachment key must be $KEY_SIZE bytes" }
        return SecretKeySpec(key, "AES")
    }

    private fun newCipher(): Cipher = Cipher.getInstance(TRANSFORMATION)

    private fun nonce(header: ByteArray, counter: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        System.arraycopy(header, 1, nonce, 0, PREFIX_SIZE)
        nonce[PREFIX_SIZE] = (counter ushr 24).toByte()
        nonce[PREFIX_SIZE + 1] = (counter ushr 16).toByte()
        nonce[PREFIX_SIZE + 2] = (counter ushr 8).toByte()
        nonce[PREFIX_SIZE + 3] = counter.toByte()
        nonce[NONCE_SIZE - 1] = if (last) 1 else 0
        return nonce
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    /** Yields verified plaintext chunks one at a time; [plain] is only valid until the next call. */
    private class ChunkReader(key: ByteArray, private val input: InputStream) {
        private val secret = secretKey(key)
        private val cipher = newCipher()
        private val header = ByteArray(HEADER_SIZE)
        private var current = ByteArray(SEALED_SIZE)
        private var ahead = ByteArray(SEALED_SIZE)
        private var length: Int
        private var counter = 0L
        private var finished = false
        val plain = ByteArray(CHUNK_SIZE)

        init {
            if (readFully(input, header) < HEADER_SIZE) throw AttachmentCorruptException("Attachment header is truncated")
            if (header[0].toInt() != VERSION) {
                throw AttachmentCorruptException("Unsupported attachment version ${header[0].toInt() and 0xFF}")
            }
            length = readFully(input, current)
        }

        /** Length of the next verified chunk in [plain], or -1 after the final chunk. */
        fun next(): Int {
            if (finished) return -1
            val aheadLength = if (length == SEALED_SIZE) readFully(input, ahead) else 0
            val last = aheadLength == 0
            if (length < TAG_SIZE) throw AttachmentCorruptException("Attachment is truncated")
            val opened = try {
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_SIZE * 8, nonce(header, counter, last)))
                cipher.updateAAD(header)
                cipher.doFinal(current, 0, length, plain, 0)
            } catch (e: GeneralSecurityException) {
                plain.fill(0)
                throw AttachmentCorruptException("Attachment failed authentication", e)
            }
            finished = last
            if (!last) {
                val spare = current
                current = ahead
                ahead = spare
                length = aheadLength
                counter++
            }
            return opened
        }
    }

    private class PlainStream(private val reader: ChunkReader, private val source: InputStream) : InputStream() {
        private var available = 0
        private var position = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (position == available && !refill()) return -1
            val count = minOf(length, available - position)
            System.arraycopy(reader.plain, position, buffer, offset, count)
            position += count
            return count
        }

        override fun available(): Int = available - position

        override fun close() = source.close()

        private fun refill(): Boolean {
            while (!ended) {
                val length = reader.next()
                if (length < 0) {
                    ended = true
                } else if (length > 0) {
                    available = length
                    position = 0
                    return true
                }
            }
            return false
        }
    }
}
