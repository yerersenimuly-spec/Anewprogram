package app.line.ui.chat

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.line.R
import app.line.ui.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Open, share and save the decrypted copy of an attachment. */
object MediaActions {
    private fun fileName(name: String?, mime: String): String {
        val clean = name?.takeIf { it.isNotBlank() }
        if (clean != null) return clean
        return when {
            mime == "image/jpeg" -> "photo.jpg"
            mime.startsWith("image/") -> "photo.${mime.substringAfter('/')}"
            mime.startsWith("audio/") -> "voice.m4a"
            else -> "file"
        }
    }

    /** A copy under cache/shared so FileProvider may expose it without opening the private store. */
    private suspend fun exposed(host: Host, source: File, name: String, messageId: String): Uri = withContext(Dispatchers.IO) {
        val folder = File(host.activity.cacheDir, "shared/${messageId.take(36).filter { it.isLetterOrDigit() || it == '-' }}").apply { mkdirs() }
        val copy = File(folder, name.replace('/', '_'))
        if (!copy.exists() || copy.length() != source.length()) source.copyTo(copy, overwrite = true)
        FileProvider.getUriForFile(host.activity, "${host.activity.packageName}.files", copy)
    }

    suspend fun share(host: Host, source: File, name: String?, mime: String, messageId: String) {
        val uri = exposed(host, source, fileName(name, mime), messageId)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        host.activity.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    suspend fun open(host: Host, source: File, name: String?, mime: String, messageId: String) {
        val uri = exposed(host, source, fileName(name, mime), messageId)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            host.activity.startActivity(view)
        } catch (_: android.content.ActivityNotFoundException) {
            host.toast(R.string.media_no_app)
        }
    }

    /** Saves to Pictures or Downloads on Android 10+; older systems get the share sheet instead. */
    suspend fun save(host: Host, source: File, name: String?, mime: String, messageId: String) {
        if (Build.VERSION.SDK_INT < 29) { share(host, source, name, mime, messageId); return }
        val saved = withContext(Dispatchers.IO) {
            val resolver = host.activity.contentResolver
            val image = mime.startsWith("image/")
            val collection = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(name, mime))
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Line")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: return@withContext false
            try {
                resolver.openOutputStream(target)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: return@withContext false
                resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(target, null, null)
                false
            }
        }
        host.toast(if (saved) R.string.media_saved else R.string.err_generic)
    }
}
