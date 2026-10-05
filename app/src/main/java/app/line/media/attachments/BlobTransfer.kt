package app.line.media.attachments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.Locale

/** Short-lived grant for one blob request, issued by the API server (`blob_ticket`). */
data class Ticket(
    val id: String,
    val method: String,
    val path: String,
    val token: String,
    val offset: Long,
    val size: Long,
) {
    /** The token is a bearer secret and stays out of logs. */
    override fun toString(): String = "Ticket(id=$id, method=$method, path=$path, offset=$offset, size=$size)"
}

/** Why a blob transfer failed. [code] is the HTTP status, or 0 when there was none. */
sealed class BlobException(val code: Int, message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Ticket missing, expired or refused (401/403): ask the API server for a new one. */
    class Unauthorized(code: Int = 401, message: String = "Blob ticket was rejected") : BlobException(code, message)

    /** The blob does not exist (404), e.g. it expired on the server. */
    class NotFound(message: String = "Blob does not exist") : BlobException(404, message)

    class TooLarge(message: String = "Blob is too large") : BlobException(413, message)

    /** Storage quota exhausted (error code containing `quota`, or 507). */
    class Quota(code: Int = 413, message: String = "Storage quota exceeded") : BlobException(code, message)

    class RateLimited(val retryAfterSeconds: Long? = null, message: String = "Too many requests") :
        BlobException(429, message)

    /** The server's state disagrees with the request (409) or the blob is not the size that was announced. */
    class Conflict(val serverOffset: Long? = null, message: String = "Blob state conflict") :
        BlobException(409, message)

    /** The connection failed or timed out; partial progress is kept. */
    class Network(cause: Throwable? = null, message: String = "Network error") : BlobException(0, message, cause)

    class Server(code: Int = 500, message: String = "Unexpected server response") : BlobException(code, message)

    /** Whether trying the same transfer again later can succeed without new input. */
    val retryable: Boolean
        get() = this is Network || this is RateLimited || (this is Server && code >= 500)

    /** Whether a new ticket (and its resume offset) is needed before retrying. */
    val needsNewTicket: Boolean
        get() = this is Unauthorized || this is Conflict
}

/**
 * Uploads and downloads encrypted blobs over HTTPS with resume support. Everything runs on [Dispatchers.IO], so
 * progress callbacks arrive on a background thread. Coroutine cancellation aborts the request and returns only after
 * the file is released, so a cancelled transfer can be restarted immediately and picks up its partial state.
 */
class BlobTransfer(client: OkHttpClient, private val origin: HttpUrl) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        require(origin.isHttps || isLoopback(origin.host)) { "Blob transfer requires https" }
    }

    /**
     * Sends the ciphertext [file] with `PUT`, starting at `ticket.offset` (what the server already holds). A `202`
     * answer carries `Upload-Offset` and the remainder is sent in a follow-up request; `201` completes. Progress is
     * `sent` of `total` bytes, counted from the start of the blob. Any failure leaves the server's partial copy in
     * place: request a new ticket and call again to resume.
     */
    suspend fun upload(ticket: Ticket, file: File, onProgress: (sent: Long, total: Long) -> Unit) {
        require(ticket.method.equals("PUT", ignoreCase = true)) { "Not an upload ticket" }
        val url = urlFor(ticket)
        withContext(Dispatchers.IO) {
            val total = file.length()
            require(total > 0) { "Nothing to upload" }
            var offset = ticket.offset
            if (offset < 0 || offset > total) {
                throw BlobException.Conflict(offset, "Server offset $offset is outside the $total byte file")
            }
            onProgress(offset, total)
            var stalls = 0
            while (offset < total) {
                val reached = put(url, ticket, file, offset, total, onProgress)
                stalls = if (reached <= offset) stalls + 1 else 0
                if (stalls > MAX_STALLS) throw BlobException.Server(202, "Server stopped accepting data at $reached")
                offset = reached
            }
            onProgress(total, total)
        }
    }

    /**
     * Fetches the blob into [dest] through `dest.part`: an existing part continues from its length with a `Range`
     * request, the final length must equal [expectedSize] and the rename is atomic. Network, rate-limit, server and
     * ticket failures and cancellation keep the part for the next attempt; a missing blob or a size mismatch delete it.
     */
    suspend fun download(ticket: Ticket, dest: File, expectedSize: Long, onProgress: (received: Long, total: Long) -> Unit) {
        require(ticket.method.equals("GET", ignoreCase = true)) { "Not a download ticket" }
        require(expectedSize > 0) { "expectedSize must be positive" }
        val url = urlFor(ticket)
        if (ticket.size > 0 && ticket.size != expectedSize) {
            throw BlobException.Conflict(message = "Server holds ${ticket.size} bytes, $expectedSize announced")
        }
        val target = dest.absoluteFile
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            try {
                var restarts = 0
                while (true) {
                    var have = part.length()
                    if (have > expectedSize) {
                        part.delete()
                        have = 0
                    }
                    if (have == expectedSize || fetch(url, ticket, part, have, expectedSize, onProgress)) break
                    part.delete()
                    if (++restarts > MAX_RESTARTS) throw BlobException.Conflict(message = "Server cannot resume the download")
                }
                commit(part, target)
            } catch (e: BlobException) {
                if (e is BlobException.NotFound || e is BlobException.Conflict) part.delete()
                throw e
            } catch (e: IOException) {
                part.delete()
                throw e
            }
            onProgress(expectedSize, expectedSize)
        }
    }

    private suspend fun put(
        url: HttpUrl,
        ticket: Ticket,
        file: File,
        offset: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var reported = offset
        val body = SliceBody(file, offset, total - offset) { written ->
            val sent = offset + written
            if (sent > reported) {
                reported = sent
                onProgress(sent, total)
            }
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${ticket.token}")
            .apply { if (offset > 0) header("Content-Range", "bytes $offset-${total - 1}/$total") }
            .put(body).build()
        return http.newCall(request).await { response, _ ->
            when (response.code) {
                201 -> {
                    val stored = storedSize(response)
                    if (stored != null && stored != total) throw BlobException.Conflict(message = "Server stored $stored of $total bytes")
                    total
                }
                202 -> response.header("Upload-Offset")?.toLongOrNull()?.takeIf { it in 0..total }
                    ?: throw BlobException.Server(202, "Server sent an invalid Upload-Offset")
                else -> throw failure(response)
            }
        }
    }

    /** Returns true when the part is complete, false when the server cannot continue it and the part must be dropped. */
    private suspend fun fetch(
        url: HttpUrl,
        ticket: Ticket,
        part: File,
        have: Long,
        expected: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${ticket.token}")
            .header("Accept-Encoding", "identity")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        return http.newCall(request).await { response, ensureActive ->
            val start = when (response.code) {
                200 -> 0L
                206 -> {
                    val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                    val first = range?.groupValues?.get(1)?.toLongOrNull()
                    val last = range?.groupValues?.get(2)?.toLongOrNull()
                    val size = range?.groupValues?.get(3)?.toLongOrNull()
                    if (first != have || last != expected - 1 || (size != null && size != expected)) return@await false
                    have
                }
                416 -> if (have > 0) return@await false else throw failure(response)
                else -> throw failure(response)
            }
            val body = response.body ?: throw BlobException.Server(response.code, "Empty response body")
            val length = body.contentLength()
            if (length >= 0 && length != expected - start) {
                throw BlobException.Conflict(message = "Server sends $length bytes, ${expected - start} expected")
            }
            onProgress(start, expected)
            var received = start
            var reported = start
            val source = body.source()
            val buffer = ByteArray(COPY_BUFFER)
            FileOutputStream(part, start > 0).use { out ->
                while (true) {
                    ensureActive()
                    val count = try {
                        source.read(buffer)
                    } catch (e: IOException) {
                        throw BlobException.Network(e)
                    }
                    if (count < 0) break
                    if (received + count > expected) throw BlobException.Conflict(message = "Server sent more than $expected bytes")
                    out.write(buffer, 0, count)
                    received += count
                    if (received - reported >= PROGRESS_STEP) {
                        reported = received
                        onProgress(received, expected)
                    }
                }
                out.flush()
                out.fd.sync()
            }
            if (received != expected) throw BlobException.Conflict(message = "Server sent $received of $expected bytes")
            true
        }
    }

    private fun commit(part: File, target: File) {
        if (part.renameTo(target)) return
        target.delete()
        if (!part.renameTo(target)) throw IOException("Cannot move ${part.name} into place")
    }

    private fun urlFor(ticket: Ticket): HttpUrl {
        require(UUID.matches(ticket.id)) { "Blob id is not a UUID" }
        require(ticket.path == "/blob/${ticket.id}") { "Unexpected blob path" }
        require(ticket.token.isNotEmpty()) { "Blob ticket has no token" }
        return origin.newBuilder().encodedPath(ticket.path).query(null).fragment(null).build()
    }

    private fun storedSize(response: Response): Long? = try {
        JSONObject(response.peekBody(ERROR_PEEK_BYTES).string()).takeIf { it.has("size") }?.getLong("size")
    } catch (e: Exception) {
        null
    }

    private fun failure(response: Response): BlobException {
        val code = response.code
        val error = try {
            JSONObject(response.peekBody(ERROR_PEEK_BYTES).string()).optString("error").lowercase(Locale.ROOT)
        } catch (e: Exception) {
            ""
        }
        return when {
            "quota" in error || code == 507 -> BlobException.Quota(code)
            code == 401 || code == 403 -> BlobException.Unauthorized(code)
            code == 404 -> BlobException.NotFound()
            code == 409 -> BlobException.Conflict(response.header("Upload-Offset")?.toLongOrNull())
            code == 413 -> BlobException.TooLarge()
            code == 429 -> BlobException.RateLimited(response.header("Retry-After")?.trim()?.toLongOrNull())
            else -> BlobException.Server(code)
        }
    }

    /**
     * Runs [call] and [block] on an I/O thread. Cancelling the coroutine cancels the OkHttp call, which unblocks any
     * pending socket read or write; the function returns only after [block] has finished with its files.
     */
    private suspend fun <T> Call.await(block: (response: Response, ensureActive: () -> Unit) -> T): T = coroutineScope {
        val canceller = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    execute().use { block(it) { ensureActive() } }
                } catch (e: BlobException) {
                    throw e
                } catch (e: IOException) {
                    ensureActive()
                    throw BlobException.Network(e)
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    /** Streams `length` bytes of [file] from [start], reporting how many have been written. One-shot: never replayed. */
    private class SliceBody(
        private val file: File,
        private val start: Long,
        private val length: Long,
        private val onWritten: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = OCTET_STREAM

        override fun contentLength(): Long = length

        // After any failure the server's stored length decides where to continue, so OkHttp must not replay the body.
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(start)
                val buffer = ByteArray(COPY_BUFFER)
                var written = 0L
                var reported = 0L
                while (written < length) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                    if (count < 0) throw EOFException("File ended during upload")
                    sink.write(buffer, 0, count)
                    written += count
                    if (written - reported >= PROGRESS_STEP || written == length) {
                        reported = written
                        onWritten(written)
                    }
                }
            }
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val COPY_BUFFER = 32 * 1024
        private const val PROGRESS_STEP = 16L * 1024
        private const val ERROR_PEEK_BYTES = 2048L
        private const val MAX_STALLS = 2
        private const val MAX_RESTARTS = 1
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** HTTPS origin of the API server for a `wss://host[:port]/signal` address (`ws://` maps to `http://`). */
        fun originOf(apiUrl: String): HttpUrl {
            val uri = URI(apiUrl)
            val secure = when (uri.scheme) {
                "wss" -> true
                "ws" -> false
                else -> throw IllegalArgumentException("Not a WebSocket address")
            }
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Address has no host")
            val builder = HttpUrl.Builder().scheme(if (secure) "https" else "http").host(host.removeSurrounding("[", "]"))
            if (uri.port != -1) builder.port(uri.port)
            return builder.build()
        }

        private fun isLoopback(host: String): Boolean = host == "localhost" || host == "::1" || host.startsWith("127.")
    }
}
