package app.line.media.attachments

import app.line.core.AttachmentCrypto
import app.line.core.AttachmentPayload
import app.line.core.MediaDraft
import app.line.core.MessageStatus
import app.line.core.StoredAttachment
import app.line.core.StoredStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory stand-in for the API server's `/blob/<id>` endpoints (see server/src/blobs.js). */
class FakeBlobServer : AutoCloseable {
    private val server = MockWebServer()
    val token = "A".repeat(43)
    val blobs = ConcurrentHashMap<String, ByteArray>()
    val partials = ConcurrentHashMap<String, ByteArray>()
    val declared = ConcurrentHashMap<String, Long>()
    val puts = CopyOnWriteArrayList<String>()
    val gets = CopyOnWriteArrayList<String>()

    /** Keep this many bytes of the next PUT, then answer 503: a connection that broke mid-upload. */
    @Volatile var failNextPutAfter = -1
    @Volatile var failGets = 0
    val origin: HttpUrl get() = server.url("/")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    /** What `blob_create` answers: remember the size, report how much is already stored. */
    fun create(id: String, size: Long): Long {
        declared[id] = size
        return (blobs[id]?.size ?: partials[id]?.size ?: 0).toLong()
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
        val id = request.path.orEmpty().removePrefix("/blob/")
        return when (request.method) {
            "PUT" -> put(id, request)
            "GET" -> get(id, request)
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun put(id: String, request: RecordedRequest): MockResponse {
        val total = declared[id] ?: return MockResponse().setResponseCode(404)
        val range = request.getHeader("Content-Range")
        puts += range.orEmpty()
        val body = request.body.readByteArray()
        val start = range?.let { RANGE.matchEntire(it)!!.groupValues[1].toLong() } ?: 0L
        val have = partials[id] ?: ByteArray(0)
        if (blobs.containsKey(id)) return MockResponse().setResponseCode(409).setHeader("Upload-Offset", total.toString())
        if (start != have.size.toLong()) return MockResponse().setResponseCode(409).setHeader("Upload-Offset", have.size.toString())
        val broken = failNextPutAfter >= 0
        val accepted = if (broken) body.copyOf(minOf(failNextPutAfter, body.size)) else body
        failNextPutAfter = -1
        val stored = have + accepted
        partials[id] = stored
        if (broken) return MockResponse().setResponseCode(503)
        if (stored.size.toLong() != total) return MockResponse().setResponseCode(202).setHeader("Upload-Offset", stored.size.toString())
        blobs[id] = stored
        partials.remove(id)
        return MockResponse().setResponseCode(201).setBody("""{"size":$total}""")
    }

    private fun get(id: String, request: RecordedRequest): MockResponse {
        gets += request.getHeader("Range").orEmpty()
        if (failGets > 0) {
            failGets--
            return MockResponse().setResponseCode(503)
        }
        val blob = blobs[id] ?: return MockResponse().setResponseCode(404)
        val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toInt() ?: 0
        val response = MockResponse().setHeader("Accept-Ranges", "bytes")
        if (start == 0) return response.setResponseCode(200).setBody(okio.Buffer().write(blob))
        return response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${blob.size - 1}/${blob.size}")
            .setBody(okio.Buffer().write(blob, start, blob.size - start))
    }

    override fun close() = server.shutdown()

    private companion object {
        val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}

class FakeGateway(private val server: FakeBlobServer) : AttachmentGateway {
    @Volatile var connected = true
    @Volatile var refuse: String? = null
    @Volatile var ticketDelayMs = 0L
    val requests = CopyOnWriteArrayList<TicketRequest>()
    val acknowledged = CopyOnWriteArrayList<String>()
    override val online: Boolean get() = connected

    override suspend fun ticket(request: TicketRequest): Ticket {
        requests += request
        if (ticketDelayMs > 0) delay(ticketDelayMs)
        refuse?.let { throw TicketRefused(it) }
        return when (request) {
            is TicketRequest.Upload -> Ticket(request.blobId, "PUT", "/blob/${request.blobId}", server.token, server.create(request.blobId, request.size), 0)
            is TicketRequest.Download -> Ticket(request.blobId, "GET", "/blob/${request.blobId}", server.token, 0, server.blobs[request.blobId]?.size?.toLong() ?: 0)
        }
    }

    override fun transfer(): BlobTransfer = BlobTransfer(OkHttpClient(), server.origin)
    override fun acknowledgeBlob(blobId: String): Boolean = acknowledged.add(blobId)
}

class FakeRepository : AttachmentRepository {
    private val rows = ConcurrentHashMap<String, StoredAttachment>()
    private val order = CopyOnWriteArrayList<String>()
    val moves = CopyOnWriteArrayList<Pair<String, StoredStage>>()

    fun add(row: StoredAttachment) {
        rows[row.messageId] = row
        order += row.messageId
    }

    fun status(id: String): MessageStatus = rows.getValue(id).status
    fun stage(id: String): StoredStage = rows.getValue(id).stage

    override fun attachment(messageId: String): StoredAttachment? = rows[messageId]

    @Synchronized
    override fun moveStage(messageId: String, to: StoredStage): Boolean {
        val row = rows[messageId] ?: return false
        if (!StoredStage.allowed(row.outgoing, row.stage, to)) return false
        val status = when {
            !row.outgoing -> row.status
            to == StoredStage.FAILED -> if (row.status == MessageStatus.PENDING) MessageStatus.FAILED else row.status
            row.stage == StoredStage.FAILED && to == StoredStage.QUEUED && row.status == MessageStatus.FAILED -> MessageStatus.PENDING
            else -> row.status
        }
        rows[messageId] = row.copy(stage = to, status = status)
        moves += messageId to to
        return true
    }

    override fun pendingUploads(limit: Int) = order.mapNotNull(rows::get).filter { it.outgoing && it.stage == StoredStage.QUEUED }.take(limit)

    override fun pendingDownloads(peer: String?, limit: Int) = order.reversed().mapNotNull(rows::get)
        .filter { !it.outgoing && it.stage == StoredStage.REMOTE && (peer == null || it.peer == peer) }.take(limit)

    override fun blobIds(): Set<String> = rows.values.map { it.blobId }.toSet()
}

class RecordingListener : AttachmentEngine.Listener {
    val attachmentChanges = AtomicInteger()
    val messageChanges = AtomicInteger()
    val uploaded = CopyOnWriteArrayList<String>()
    override fun attachmentChanged() { attachmentChanges.incrementAndGet() }
    override fun messageChanged() { messageChanges.incrementAndGet() }
    override fun uploaded(messageId: String) { uploaded += messageId }
}

const val PEER = "12345678"

/** A sealed attachment on disk plus the row that describes it. */
class Fixture(val files: AttachmentFiles, val plain: ByteArray, val key: ByteArray, val payload: AttachmentPayload, val messageId: String) {
    val blobId: String get() = payload.blobId

    fun row(outgoing: Boolean, stage: StoredStage, status: MessageStatus = if (outgoing) MessageStatus.PENDING else MessageStatus.RECEIVED) =
        StoredAttachment(messageId, PEER, outgoing, stage, blobId, payload.size, false, status, payload)

    companion object {
        fun sealed(files: AttachmentFiles, size: Int = 150_000, draft: MediaDraft = MediaDraft.file("report.pdf", "application/pdf")): Fixture {
            val plain = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
            val key = AttachmentCrypto.newKey()
            val blobId = UUID.randomUUID().toString()
            files.seal(blobId, key, ByteArrayInputStream(plain))
            return Fixture(files, plain, key, draft.toPayload(blobId, key, plain.size.toLong()), UUID.randomUUID().toString())
        }

        /** The ciphertext as a server would hold it, without writing it to the device. */
        fun remote(files: AttachmentFiles, server: FakeBlobServer, size: Int = 150_000, draft: MediaDraft = MediaDraft.file("report.pdf", "application/pdf")): Fixture {
            val fixture = sealed(files, size, draft)
            server.blobs[fixture.blobId] = fixture.files.blob(fixture.blobId).readBytes()
            fixture.files.deleteBlob(fixture.blobId)
            return fixture
        }
    }
}

suspend fun eventually(timeoutMs: Long = 10_000, check: () -> Boolean) {
    withTimeout(timeoutMs) { while (!check()) delay(20) }
}

fun File.newestChild(): File? = listFiles()?.maxByOrNull { it.lastModified() }
