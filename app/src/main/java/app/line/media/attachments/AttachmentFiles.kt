package app.line.media.attachments

import app.line.core.AttachmentCorruptException
import app.line.core.AttachmentCrypto
import app.line.core.AttachmentTooLarge
import app.line.core.MediaLimits
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Where attachment bytes live. Ciphertext stays in `filesDir/attachments/<blobId>.enc` (the sender's own copy too);
 * plaintext only ever exists in `cacheDir/shared/<messageId>/<name>` for the FileProvider, and is swept after a day.
 * Pure `java.io`, so it runs in JVM tests.
 */
class AttachmentFiles(filesDir: File, cacheDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    val blobDir = File(filesDir, DIRECTORY)
    val sharedDir = File(cacheDir, SHARED)
    private val outgoingDir = File(cacheDir, OUTGOING)

    fun blob(blobId: String): File = File(blobDir, "${uuid(blobId)}.enc")

    /**
     * Encrypts [source] with [key] into the blob file of [blobId]: written to `.tmp`, flushed to disk, then renamed, so
     * a crash never leaves a half-written `.enc`. Returns the plaintext size. Throws [AttachmentTooLarge] as soon as
     * the source delivers more than [maxPlain] bytes. The streams stay open for the caller.
     */
    fun seal(blobId: String, key: ByteArray, source: InputStream, maxPlain: Long = MediaLimits.MAX_FILE_BYTES): Long {
        val target = blob(blobId)
        val temp = File(target.path + TEMP_SUFFIX)
        blobDir.mkdirs()
        var done = false
        try {
            val plain = FileOutputStream(temp).use { file ->
                val output = BufferedOutputStream(file, BUFFER)
                val count = AttachmentCrypto.encrypt(key, Limited(source, maxPlain), output)
                output.flush()
                file.fd.sync()
                count
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Cannot move ${temp.name} into place")
            }
            done = true
            return plain
        } finally {
            if (!done) temp.delete()
        }
    }

    /** Opens the whole blob once: its length and every authentication tag must match [plainSize]. */
    fun verify(key: ByteArray, blob: File, plainSize: Long) {
        val expected = AttachmentCrypto.encryptedSize(plainSize)
        if (blob.length() != expected) throw AttachmentCorruptException("Blob has ${blob.length()} bytes, $expected expected")
        val opened = FileInputStream(blob).use { AttachmentCrypto.decrypt(key, it, Discard) }
        if (opened != plainSize) throw AttachmentCorruptException("Blob holds $opened bytes of plaintext, $plainSize expected")
    }

    /** Decrypted copy for playing, viewing or sharing; reused while it is complete. */
    fun plainCopy(messageId: String, name: String, blob: File, key: ByteArray, plainSize: Long): File {
        val directory = File(sharedDir, uuid(messageId))
        directory.mkdirs()
        val target = File(directory, name)
        if (target.isFile && target.length() == plainSize) {
            target.setLastModified(clock())
            directory.setLastModified(clock())
            return target
        }
        val temp = File(directory, "$name$TEMP_SUFFIX")
        var done = false
        try {
            val written = FileInputStream(blob).use { input ->
                FileOutputStream(temp).use { file ->
                    val output = BufferedOutputStream(file, BUFFER)
                    val count = AttachmentCrypto.decrypt(key, input, output)
                    output.flush()
                    count
                }
            }
            if (written != plainSize) throw AttachmentCorruptException("Blob holds $written bytes of plaintext, $plainSize expected")
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Cannot move ${temp.name} into place")
            done = true
            return target
        } finally {
            if (!done) temp.delete()
        }
    }

    fun deleteBlob(blobId: String) {
        val blob = blob(blobId)
        blob.delete()
        File(blob.path + BlobTransfer.PART_SUFFIX).delete()
        File(blob.path + TEMP_SUFFIX).delete()
    }

    /** Everything this device keeps for one message: ciphertext, partial download and decrypted copy. */
    fun deleteMessage(messageId: String, blobId: String?) {
        blobId?.let(::deleteBlob)
        shred(File(sharedDir, uuid(messageId)))
    }

    /** Deletes decrypted copies not touched for [maxAgeMs]; returns how many entries went. */
    fun sweepShared(maxAgeMs: Long): Int = sweep(sharedDir, maxAgeMs)

    /** Deletes leftovers of picked files and recordings in `cacheDir/outgoing` older than [maxAgeMs]. */
    fun sweepOutgoing(maxAgeMs: Long): Int = sweep(outgoingDir, maxAgeMs)

    /**
     * Deletes files in the blob directory that no message row refers to (a crash between writing the blob and saving
     * the message). Anything younger than [minAgeMs] is left alone: it may belong to a send that is still being saved.
     */
    fun sweepOrphans(referencedBlobIds: Set<String>, minAgeMs: Long): Int {
        val now = clock()
        var removed = 0
        blobDir.listFiles()?.forEach { file ->
            val id = file.name.substringBefore('.')
            val referenced = id.lowercase() in referencedBlobIds
            if (!referenced && now - file.lastModified() >= minAgeMs && file.delete()) removed++
        }
        return removed
    }

    private fun sweep(directory: File, maxAgeMs: Long): Int {
        val now = clock()
        var removed = 0
        directory.listFiles()?.forEach { entry ->
            val newest = if (entry.isDirectory) entry.walkTopDown().maxOf { it.lastModified() } else entry.lastModified()
            if (now - newest >= maxAgeMs && shred(entry)) removed++
        }
        return removed
    }

    /** Overwrites plaintext with zeros before deleting it; best effort, flash storage may keep old blocks. */
    private fun shred(target: File): Boolean {
        target.walkBottomUp().filter { it.isFile }.forEach { file ->
            try {
                RandomAccessFile(file, "rw").use { out ->
                    val zeros = ByteArray(BUFFER)
                    var left = out.length()
                    while (left > 0) {
                        val count = minOf(left, zeros.size.toLong()).toInt()
                        out.write(zeros, 0, count)
                        left -= count
                    }
                    out.fd.sync()
                }
            } catch (e: IOException) {
                // Deleting is what matters.
            }
        }
        return target.deleteRecursively()
    }

    private fun uuid(value: String): String {
        require(UUID.matches(value)) { "Not a UUID" }
        return value.lowercase()
    }

    private class Limited(source: InputStream, private val limit: Long) : FilterInputStream(source) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) add(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) add(read.toLong())
            return read
        }

        private fun add(bytes: Long) {
            count += bytes
            if (count > limit) throw AttachmentTooLarge(count, limit)
        }
    }

    private object Discard : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        const val DIRECTORY = "attachments"
        const val SHARED = "shared"
        const val OUTGOING = "outgoing" // ImageProcessor.OUTGOING_DIR
        const val TEMP_SUFFIX = ".tmp"
        private const val BUFFER = 64 * 1024
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
