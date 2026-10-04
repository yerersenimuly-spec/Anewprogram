package app.line.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.line.media.attachments.ImageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

/** Decodes chat images off the main thread, downsampled to what is drawn, behind a size-bounded LRU cache. */
object ChatImages {
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(4096)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = HashMap<String, Deferred<Bitmap?>>()

    fun thumbKey(id: String) = "t:$id"
    fun fullKey(id: String) = "f:$id"

    fun peek(key: String): Bitmap? = cache.get(key)

    /** Tiny preview embedded in the message; decoding is immediate. */
    fun thumb(id: String, bytes: ByteArray): Bitmap? {
        val key = thumbKey(id)
        cache.get(key)?.let { return it }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** The decrypted image, decoded so its long edge is about [maxEdge] pixels. Concurrent callers share one decode. */
    suspend fun full(id: String, maxEdge: Int, source: suspend () -> File): Bitmap? {
        val key = fullKey(id)
        cache.get(key)?.let { return it }
        val job = synchronized(pending) {
            pending.getOrPut(key) {
                scope.async {
                    try {
                        decode(source(), maxEdge)?.also { cache.put(key, it) }
                    } finally {
                        synchronized(pending) { pending.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    /** Drops cached copies of one message, for example after its image was replaced. */
    fun forget(id: String) { cache.remove(fullKey(id)); cache.remove(thumbKey(id)) }

    fun decode(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageProcessor.computeSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            null
        }
    }
}

/** Decodes a picked photo for a preview, honouring its EXIF orientation. Runs on the IO dispatcher. */
suspend fun decodeUri(context: android.content.Context, uri: android.net.Uri, maxEdge: Int): Bitmap? = kotlinx.coroutines.withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply { inSampleSize = ImageProcessor.computeSampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (_: java.io.IOException) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val transform = ImageProcessor.orientationTransform(orientation)
        if (transform.degrees == 0 && !transform.mirror) return@withContext bitmap
        val matrix = android.graphics.Matrix().apply {
            postRotate(transform.degrees.toFloat())
            if (transform.mirror) postScale(-1f, 1f)
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    } catch (_: java.io.IOException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
