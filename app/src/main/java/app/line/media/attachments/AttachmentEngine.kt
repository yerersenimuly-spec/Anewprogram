package app.line.media.attachments

import app.line.core.AttachmentCorruptException
import app.line.core.AttachmentUnavailable
import app.line.core.AttachmentViews
import app.line.core.AutoFetchPolicy
import app.line.core.ProgressThrottle
import app.line.core.SafeFileName
import app.line.core.StoredAttachment
import app.line.core.StoredStage
import app.line.core.TransferStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Persistent state of attachments; every call blocks and runs on the engine's I/O dispatcher. */
interface AttachmentRepository {
    fun attachment(messageId: String): StoredAttachment?

    /**
     * Compare-and-set along [StoredStage.allowed]; false when the row is gone or the move is not allowed. Keeps the
     * message in step: an outgoing row that fails marks a pending message failed, a retry makes it pending again.
     */
    fun moveStage(messageId: String, to: StoredStage): Boolean

    /** Outgoing attachments whose blob is not on the server yet, oldest first. */
    fun pendingUploads(limit: Int): List<StoredAttachment>

    /** Incoming attachments not fetched yet, newest first; of one conversation when [peer] is given. */
    fun pendingDownloads(peer: String?, limit: Int): List<StoredAttachment>

    /** Blob ids of every attachment row, lower case. */
    fun blobIds(): Set<String>
}

sealed class TicketRequest {
    class Upload(val blobId: String, val to: String, val size: Long) : TicketRequest()
    class Download(val blobId: String) : TicketRequest()
}

/** The connection to the API server, as the engine sees it. */
interface AttachmentGateway {
    /** True while the signaling connection can issue tickets. */
    val online: Boolean

    /** Throws [TicketRefused] when the server answers with an error, [BlobException.Network] when it does not answer. */
    suspend fun ticket(request: TicketRequest): Ticket

    fun transfer(): BlobTransfer?

    /** Tells the server that an incoming blob is stored here and can go (`blob_ack`). False when it could not be sent. */
    fun acknowledgeBlob(blobId: String): Boolean
}

/**
 * Runs uploads and downloads of encrypted blobs. An attachment is only ever in one transfer at a time (calls are
 * idempotent per message id), at most [SLOTS] uploads and [SLOTS] downloads run together, and a transfer that cannot
 * finish leaves its persisted stage untouched so the next start, reconnect or retry resumes it. Stage changes are
 * written to the repository first, then mirrored into [views] and announced to the [Listener].
 */
class AttachmentEngine(
    private val scope: CoroutineScope,
    private val repository: AttachmentRepository,
    private val files: AttachmentFiles,
    private val gateway: AttachmentGateway,
    private val views: AttachmentViews,
    private val listener: Listener,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val throttle: ProgressThrottle = ProgressThrottle(),
) {
    interface Listener {
        /** An attachment changed stage or progress; screens re-read [AttachmentViews]. */
        fun attachmentChanged()

        /** A message changed status because of its attachment (failed, retried). */
        fun messageChanged()

        /** The blob of an outgoing message is on the server: its envelope may be sent now. */
        fun uploaded(messageId: String)
    }

    enum class Outcome { DONE, WAITING, FAILED, EXPIRED, GONE }

    private class Active(val direction: TransferDirection) {
        lateinit var job: Deferred<Outcome>
        @Volatile var running = false
        @Volatile var progress = 0f
    }

    private class Budget {
        var attempt = 0
        var renewals = 0
    }

    private sealed interface Step {
        class Done(val outcome: Outcome) : Step
        class Again(val delayMs: Long) : Step
    }

    private val lock = Any()
    private val active = HashMap<String, Active>()
    private val scheduled = HashMap<String, Job>()
    private val wanted = LinkedHashSet<String>()
    private val uploadSlots = Semaphore(SLOTS)
    private val downloadSlots = Semaphore(SLOTS)
    private val decryptLock = Mutex()

    /** 0..1 while the message is transferring, null otherwise. */
    fun progressOf(messageId: String): Float? = synchronized(lock) { active[messageId]?.takeIf { it.running }?.progress }

    fun isActive(messageId: String): Boolean = synchronized(lock) { active.containsKey(messageId) }

    /** Starts (or joins) the upload of an outgoing attachment. Cheap to call again. */
    fun upload(messageId: String): Deferred<Outcome> = start(messageId, TransferDirection.UPLOAD)

    /**
     * Downloads an incoming attachment and waits for the outcome. Offline, the request is remembered and carried out
     * when the connection returns ([Outcome.WAITING]).
     */
    suspend fun fetch(messageId: String): Outcome {
        if (!gateway.online) {
            synchronized(lock) { wanted.add(messageId) }
            return Outcome.WAITING
        }
        return start(messageId, TransferDirection.DOWNLOAD).outcome()
    }

    /** Fetches [messageId] without a tap when [AutoFetchPolicy] allows it in the state described by [context]. */
    suspend fun autoFetch(messageId: String, context: AutoFetchPolicy.Context) {
        val stored = load(messageId) ?: return
        if (shouldAutoFetch(stored, context)) start(messageId, TransferDirection.DOWNLOAD)
    }

    /** The chat of [peer] was opened: fetch what its photos and voice messages need. */
    suspend fun autoFetchConversation(peer: String, context: AutoFetchPolicy.Context) {
        if (!gateway.online) return
        withContext(io) { repository.pendingDownloads(peer, FETCH_WINDOW) }
            .filter { shouldAutoFetch(it, context) }
            .forEach { start(it.messageId, TransferDirection.DOWNLOAD) }
    }

    /** The connection is back: resume every queued upload, requested downloads, and a bounded batch of automatic ones. */
    suspend fun onOnline(contextOf: (peer: String) -> AutoFetchPolicy.Context) {
        withContext(io) { repository.pendingUploads(UPLOAD_WINDOW) }.forEach { start(it.messageId, TransferDirection.UPLOAD) }
        val requested = synchronized(lock) { wanted.toList().also { wanted.clear() } }
        requested.forEach { start(it, TransferDirection.DOWNLOAD) }
        var background = 0
        for (stored in withContext(io) { repository.pendingDownloads(null, FETCH_WINDOW) }) {
            val context = contextOf(stored.peer)
            if (!shouldAutoFetch(stored, context)) continue
            if (!context.chatOpen && ++background > AutoFetchPolicy.BACKGROUND_BATCH) continue
            start(stored.messageId, TransferDirection.DOWNLOAD)
        }
    }

    /** Stops the transfer of [messageId] and waits until its files are released. */
    suspend fun cancel(messageId: String) {
        val running = synchronized(lock) {
            scheduled.remove(messageId)?.cancel()
            wanted.remove(messageId)
            active.remove(messageId)
        }
        running?.job?.cancelAndJoin()
    }

    /** After the rows are gone: stops transfers and deletes every file of the message. */
    suspend fun discard(messageId: String, blobId: String?, acknowledge: Boolean) {
        cancel(messageId)
        views.remove(messageId)
        withContext(io) { files.deleteMessage(messageId, blobId) }
        if (acknowledge && blobId != null) gateway.acknowledgeBlob(blobId)
    }

    /** Decrypted copy in the cache. Fetches an incoming attachment first; throws [AttachmentUnavailable] if it cannot. */
    suspend fun plainFile(messageId: String): File {
        var stored = load(messageId) ?: throw AttachmentUnavailable(TransferStage.FAILED)
        if (!stored.outgoing && stored.stage != StoredStage.READY) {
            fetch(messageId)
            stored = load(messageId) ?: throw AttachmentUnavailable(TransferStage.FAILED)
            if (stored.stage != StoredStage.READY) throw AttachmentUnavailable(stored.stage.toTransferStage(false))
        }
        val blob = files.blob(stored.blobId)
        if (!withContext(io) { blob.isFile }) {
            if (stored.outgoing) throw AttachmentUnavailable(TransferStage.FAILED)
            // The file was lost: fetch it again if the server still has it.
            if (withContext(io) { repository.moveStage(messageId, StoredStage.REMOTE) }) show(messageId, TransferStage.QUEUED, 0f)
            throw AttachmentUnavailable(TransferStage.QUEUED)
        }
        return decryptLock.withLock {
            withContext(io) {
                try {
                    files.plainCopy(messageId, SafeFileName.forPayload(stored.payload), blob, stored.payload.key, stored.payload.plainSize)
                } catch (e: AttachmentCorruptException) {
                    if (!stored.outgoing) {
                        files.deleteBlob(stored.blobId)
                        repository.moveStage(messageId, StoredStage.REMOTE)
                    }
                    throw e
                }
            }
        }
    }

    /** Removes decrypted copies older than a day and files nothing refers to. Safe to run while sending. */
    suspend fun sweep() = withContext(io) {
        files.sweepShared(SHARED_MAX_AGE_MS)
        files.sweepOutgoing(OUTGOING_MAX_AGE_MS)
        files.sweepOrphans(repository.blobIds(), ORPHAN_MIN_AGE_MS)
    }

    private fun shouldAutoFetch(stored: StoredAttachment, context: AutoFetchPolicy.Context): Boolean =
        !stored.outgoing && stored.stage == StoredStage.REMOTE && AutoFetchPolicy.shouldFetch(stored.payload.type, stored.size, context)

    private fun start(messageId: String, direction: TransferDirection): Deferred<Outcome> = synchronized(lock) {
        val existing = active[messageId]
        if (existing != null && existing.direction == direction) return existing.job
        scheduled.remove(messageId)?.cancel()
        val entry = Active(direction)
        val job = scope.async(start = CoroutineStart.LAZY) { guarded(messageId, direction, entry) }
        entry.job = job
        active[messageId] = entry
        job.invokeOnCompletion { synchronized(lock) { if (active[messageId] === entry) active.remove(messageId) } }
        job.start()
        job
    }

    private suspend fun guarded(messageId: String, direction: TransferDirection, entry: Active): Outcome {
        val slots = if (direction == TransferDirection.UPLOAD) uploadSlots else downloadSlots
        val budget = Budget()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val step = slots.withPermit {
                    entry.running = true
                    try {
                        if (direction == TransferDirection.UPLOAD) uploadOnce(messageId, entry, budget) else downloadOnce(messageId, entry, budget)
                    } finally {
                        entry.running = false
                    }
                }
                when (step) {
                    is Step.Done -> return step.outcome
                    is Step.Again -> delay(step.delayMs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A bug or an unexpected store failure must not strand the attachment: try again later.
            retryLater(messageId, direction, TransferPolicy.IDLE_RETRY_MS)
            return Outcome.WAITING
        }
    }

    private suspend fun uploadOnce(id: String, entry: Active, budget: Budget): Step {
        val stored = load(id) ?: return Step.Done(Outcome.GONE)
        if (!stored.outgoing) return Step.Done(Outcome.GONE)
        when (stored.stage) {
            StoredStage.UPLOADED -> return Step.Done(Outcome.DONE)
            StoredStage.QUEUED -> Unit
            StoredStage.FAILED -> return Step.Done(Outcome.FAILED)
            else -> return Step.Done(Outcome.GONE)
        }
        val blob = files.blob(stored.blobId)
        if (!withContext(io) { blob.isFile && blob.length() == stored.size }) return fail(stored, expired = false)
        if (!gateway.online) return Step.Done(Outcome.WAITING)
        val transfer = gateway.transfer() ?: return Step.Done(Outcome.WAITING)
        show(id, TransferStage.TRANSFERRING, entry.progress)
        try {
            val ticket = gateway.ticket(TicketRequest.Upload(stored.blobId, stored.peer, stored.size))
            transfer.upload(ticket, blob) { sent, total -> progress(id, entry, sent, total) }
            withContext(io) { repository.moveStage(id, StoredStage.UPLOADED) }
            val after = load(id)
            if (after?.stage != StoredStage.UPLOADED) return Step.Done(Outcome.GONE)
            show(id, TransferStage.READY, 1f)
            listener.uploaded(id)
            return Step.Done(Outcome.DONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return react(stored, TransferDirection.UPLOAD, e, budget)
        }
    }

    private suspend fun downloadOnce(id: String, entry: Active, budget: Budget): Step {
        var stored = load(id) ?: return Step.Done(Outcome.GONE)
        if (stored.outgoing) return Step.Done(Outcome.GONE)
        val blob = files.blob(stored.blobId)
        when (stored.stage) {
            StoredStage.READY -> {
                if (withContext(io) { blob.isFile }) return Step.Done(Outcome.DONE)
                withContext(io) { repository.moveStage(id, StoredStage.REMOTE) }
                stored = load(id) ?: return Step.Done(Outcome.GONE)
            }
            StoredStage.REMOTE -> Unit
            StoredStage.FAILED -> return Step.Done(Outcome.FAILED)
            StoredStage.EXPIRED -> return Step.Done(Outcome.EXPIRED)
            else -> return Step.Done(Outcome.GONE)
        }
        // A crash between the last byte and the stage change leaves a complete blob behind: adopt it.
        if (withContext(io) { opens(stored, blob) }) return finishDownload(stored)
        if (!gateway.online) return Step.Done(Outcome.WAITING)
        val transfer = gateway.transfer() ?: return Step.Done(Outcome.WAITING)
        show(id, TransferStage.TRANSFERRING, entry.progress)
        try {
            val ticket = gateway.ticket(TicketRequest.Download(stored.blobId))
            transfer.download(ticket, blob, stored.size) { received, total -> progress(id, entry, received, total) }
            withContext(io) { files.verify(stored.payload.key, blob, stored.payload.plainSize) }
            return finishDownload(stored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is AttachmentCorruptException) withContext(io) { files.deleteBlob(stored.blobId) }
            return react(stored, TransferDirection.DOWNLOAD, e, budget)
        }
    }

    private suspend fun finishDownload(stored: StoredAttachment): Step {
        withContext(io) { repository.moveStage(stored.messageId, StoredStage.READY) }
        if (load(stored.messageId)?.stage != StoredStage.READY) return Step.Done(Outcome.GONE)
        show(stored.messageId, TransferStage.READY, 1f)
        gateway.acknowledgeBlob(stored.blobId)
        return Step.Done(Outcome.DONE)
    }

    private fun opens(stored: StoredAttachment, blob: File): Boolean = blob.isFile && blob.length() == stored.size && try {
        files.verify(stored.payload.key, blob, stored.payload.plainSize)
        true
    } catch (e: Exception) {
        blob.delete()
        false
    }

    private suspend fun react(stored: StoredAttachment, direction: TransferDirection, error: Exception, budget: Budget): Step =
        when (val decision = TransferPolicy.decide(direction, error, budget.attempt, budget.renewals)) {
            is TransferPolicy.Decision.Retry -> {
                budget.attempt++
                if (decision.newTicket && decision.delayMs == 0L) budget.renewals++
                show(stored.messageId, stored.stage.toTransferStage(false), 0f)
                Step.Again(decision.delayMs)
            }
            is TransferPolicy.Decision.Wait -> {
                show(stored.messageId, stored.stage.toTransferStage(false), 0f)
                retryLater(stored.messageId, direction, decision.delayMs)
                Step.Done(Outcome.WAITING)
            }
            is TransferPolicy.Decision.Fail -> fail(stored, decision.expired)
        }

    private suspend fun fail(stored: StoredAttachment, expired: Boolean): Step {
        val target = if (expired) StoredStage.EXPIRED else StoredStage.FAILED
        val moved = withContext(io) { repository.moveStage(stored.messageId, target) }
        show(stored.messageId, target.toTransferStage(false), 0f)
        if (moved && stored.outgoing) listener.messageChanged()
        return Step.Done(if (expired) Outcome.EXPIRED else Outcome.FAILED)
    }

    private fun retryLater(messageId: String, direction: TransferDirection, delayMs: Long) {
        synchronized(lock) {
            scheduled.remove(messageId)?.cancel()
            scheduled[messageId] = scope.launch {
                delay(delayMs)
                synchronized(lock) { scheduled.remove(messageId) }
                if (gateway.online) start(messageId, direction)
            }
        }
    }

    private fun progress(id: String, entry: Active, done: Long, total: Long) {
        val fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        entry.progress = fraction
        views.update(id) { it.copy(stage = TransferStage.TRANSFERRING, progress = fraction) }
        if (throttle.tryAcquire()) listener.attachmentChanged()
    }

    private fun show(id: String, stage: TransferStage, progress: Float) {
        views.update(id) { it.copy(stage = stage, progress = if (stage == TransferStage.READY) 1f else progress) }
        listener.attachmentChanged()
    }

    private suspend fun load(id: String): StoredAttachment? = withContext(io) { repository.attachment(id) }

    private suspend fun Deferred<Outcome>.outcome(): Outcome = try {
        await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Outcome.GONE
    }

    companion object {
        const val SLOTS = 2
        private const val FETCH_WINDOW = 64
        private const val UPLOAD_WINDOW = 200
        private const val SHARED_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val OUTGOING_MAX_AGE_MS = 60L * 60 * 1000
        private const val ORPHAN_MIN_AGE_MS = 10L * 60 * 1000
    }
}
