@file:Suppress("DEPRECATION")

package app.line.media.attachments

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.line.core.AttachmentPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** A downscaled JPEG without metadata, ready to be encrypted, plus the data for the message payload. */
class PreparedImage(
    val file: File,
    val width: Int,
    val height: Int,
    /** JPEG of at most [ImageProcessor.THUMB_MAX_BYTES] bytes. */
    val thumb: ByteArray,
    val size: Long,
    val mime: String = ImageProcessor.JPEG_MIME,
)

/** What the content provider says about a picked file; [size] is -1 when unknown. */
data class FileInfo(val name: String?, val size: Long, val mime: String)

/**
 * Turns picked images into small JPEGs and copies picked files into the cache, streaming (files are never read into
 * memory) and only from `content://` URIs: a `file://` URI shared by another app could point into this app's private
 * storage. Outputs go to `cacheDir/outgoing`; the caller deletes them once sent. Needs a device, except for the pure
 * helpers ([computeSampleSize], [scaledSize], [orientationTransform], [isTooLarge]).
 */
object ImageProcessor {
    const val MAX_EDGE = 2048
    const val JPEG_QUALITY = 85
    const val JPEG_MIME = "image/jpeg"
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    const val THUMB_MAX_BYTES = AttachmentPayload.MAX_THUMB_BYTES
    const val OUTGOING_DIR = "outgoing"
    private const val MAX_SAMPLE = 64
    private const val THUMB_QUALITY = 60
    private const val THUMB_FALLBACK_QUALITY = 40
    private const val MAX_OOM_RETRIES = 2
    private const val COPY_BUFFER = 64 * 1024
    private val THUMB_EDGES = intArrayOf(96, 80, 64, 48, 32, 16)

    /** How an EXIF orientation maps to a matrix: rotate by [degrees], then mirror horizontally if [mirror]. */
    data class Transform(val degrees: Int, val mirror: Boolean) {
        val swapsAxes: Boolean get() = degrees % 180 != 0
    }

    fun isTooLarge(size: Long): Boolean = size > MAX_FILE_BYTES

    /**
     * Largest power-of-two `inSampleSize` that keeps the long edge at or above [maxEdge], so the decoded bitmap is
     * never smaller than the target and a single exact downscale finishes the job.
     */
    fun computeSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge && sample < MAX_SAMPLE) sample *= 2
        return sample
    }

    /** Dimensions of ([width] x [height]) scaled down to at most [maxEdge] on the long edge, never below 1 px. */
    fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        require(maxEdge > 0) { "maxEdge must be positive" }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longEdge
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    fun orientationTransform(orientation: Int): Transform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Transform(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> Transform(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Transform(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> Transform(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> Transform(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> Transform(270, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> Transform(270, false)
        else -> Transform(0, false)
    }

    /** Name, size and MIME type as reported by the provider (blocking; call off the main thread). Best effort: never throws for an unreadable provider. */
    fun fileInfo(context: Context, uri: Uri): FileInfo {
        requireContentUri(uri)
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        } catch (e: RuntimeException) {
            name = null
            size = -1
        }
        val declared = try {
            resolver.getType(uri)
        } catch (e: RuntimeException) {
            null
        }
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val guessed = if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return FileInfo(AttachmentPayload.sanitizeName(name), size, AttachmentPayload.normalizeMime(declared ?: guessed))
    }

    /**
     * Copies [uri] into a new file in the cache, aborting with [AttachmentPrepareException.Reason.TOO_LARGE] as soon
     * as more than [max] bytes have been read. The temp file is removed on failure and cancellation.
     */
    suspend fun copyToCache(context: Context, uri: Uri, max: Long = MAX_FILE_BYTES): File = withContext(Dispatchers.IO) {
        requireContentUri(uri)
        val target = File.createTempFile("file", ".bin", outgoingDir(context))
        var done = false
        try {
            open(context.contentResolver, uri).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > max) throw AttachmentPrepareException(AttachmentPrepareException.Reason.TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                }
            }
            done = true
            target
        } finally {
            if (!done) target.delete()
        }
    }

    /**
     * Decodes [uri] (long edge at most [MAX_EDGE], EXIF orientation applied, transparency flattened onto white),
     * re-encodes it as JPEG quality [JPEG_QUALITY] (which drops all metadata, including location) and builds the
     * thumbnail. Decoding retries at a coarser sample size when memory runs out.
     */
    suspend fun prepare(context: Context, uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
        requireContentUri(uri)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNSUPPORTED, "Not a decodable image")
        }
        ensureActive()
        val transform = orientationTransform(readOrientation(resolver, uri))
        val target = File.createTempFile("image", ".jpg", outgoingDir(context))
        var done = false
        var image: Bitmap? = null
        try {
            val rendered = renderWithRetry(resolver, uri, bounds.outWidth, bounds.outHeight, transform)
            image = rendered
            ensureActive()
            FileOutputStream(target).use { output ->
                if (!rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNSUPPORTED, "JPEG encoding failed")
                }
            }
            val thumb = try {
                thumbnail(rendered)
            } catch (e: OutOfMemoryError) {
                throw AttachmentPrepareException(AttachmentPrepareException.Reason.OUT_OF_MEMORY, cause = e)
            }
            done = true
            PreparedImage(target, rendered.width, rendered.height, thumb, target.length())
        } finally {
            image?.recycle()
            if (!done) target.delete()
        }
    }

    private fun renderWithRetry(resolver: ContentResolver, uri: Uri, width: Int, height: Int, transform: Transform): Bitmap {
        var sample = computeSampleSize(width, height, MAX_EDGE)
        var attempt = 0
        while (true) {
            try {
                return render(resolver, uri, sample, transform)
            } catch (e: OutOfMemoryError) {
                if (++attempt > MAX_OOM_RETRIES) {
                    throw AttachmentPrepareException(AttachmentPrepareException.Reason.OUT_OF_MEMORY, cause = e)
                }
                sample *= 2
            }
        }
    }

    private fun render(resolver: ContentResolver, uri: Uri, sample: Int, transform: Transform): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNSUPPORTED, "Not a decodable image")
        var current = decoded
        try {
            val turned = if (transform.swapsAxes) decoded.height to decoded.width else decoded.width to decoded.height
            val (width, height) = scaledSize(turned.first, turned.second, MAX_EDGE)
            val matrix = Matrix()
            matrix.setRotate(transform.degrees.toFloat())
            if (transform.mirror) matrix.postScale(-1f, 1f)
            if (width != turned.first || height != turned.second) {
                matrix.postScale(width.toFloat() / turned.first, height.toFloat() / turned.second)
            }
            if (!matrix.isIdentity) {
                current = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (current !== decoded) decoded.recycle()
            }
            if (maxOf(current.width, current.height) > MAX_EDGE) {
                val (fitWidth, fitHeight) = scaledSize(current.width, current.height, MAX_EDGE)
                current = replace(current, Bitmap.createScaledBitmap(current, fitWidth, fitHeight, true))
            }
            if (current.hasAlpha()) {
                val flat = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(current, 0f, 0f, null)
                }
                current = replace(current, flat)
            }
            return current
        } catch (e: Throwable) {
            if (current !== decoded) current.recycle()
            decoded.recycle()
            throw e
        }
    }

    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (new !== old) old.recycle()
        return new
    }

    private fun thumbnail(source: Bitmap): ByteArray {
        for (edge in THUMB_EDGES) {
            val (width, height) = scaledSize(source.width, source.height, edge)
            val small = downscale(source, width, height)
            try {
                for (quality in intArrayOf(THUMB_QUALITY, THUMB_FALLBACK_QUALITY)) {
                    val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    if (bytes.size <= THUMB_MAX_BYTES) return bytes
                }
            } finally {
                if (small !== source) small.recycle()
            }
        }
        throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNSUPPORTED, "Thumbnail does not fit")
    }

    /** Halves repeatedly before the last step: one bilinear pass over a large reduction aliases badly. */
    private fun downscale(source: Bitmap, width: Int, height: Int): Bitmap {
        var current = source
        while (current.width / 2 >= width && current.height / 2 >= height) {
            current = replaceUnlessSource(current, Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true), source)
        }
        return replaceUnlessSource(current, Bitmap.createScaledBitmap(current, width, height, true), source)
    }

    private fun replaceUnlessSource(old: Bitmap, new: Bitmap, source: Bitmap): Bitmap {
        if (new !== old && old !== source) old.recycle()
        return new
    }

    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = try {
        open(resolver, uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (e: RuntimeException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun open(resolver: ContentResolver, uri: Uri): InputStream {
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNREADABLE, cause = e)
        } catch (e: SecurityException) {
            throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNREADABLE, cause = e)
        }
        return BufferedInputStream(stream ?: throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNREADABLE), COPY_BUFFER)
    }

    private fun outgoingDir(context: Context): File =
        File(context.cacheDir, OUTGOING_DIR).also { it.mkdirs() }

    private fun requireContentUri(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw AttachmentPrepareException(AttachmentPrepareException.Reason.UNSUPPORTED, "Only content:// URIs are accepted")
        }
    }
}
