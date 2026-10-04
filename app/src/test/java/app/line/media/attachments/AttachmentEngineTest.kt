package app.line.media.attachments

import app.line.core.AttachmentViews
import app.line.core.AutoFetchPolicy
import app.line.core.MediaDraft
import app.line.core.MessageStatus
import app.line.core.StoredStage
import app.line.core.TransferStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var files: AttachmentFiles
    private lateinit var server: FakeBlobServer
    private lateinit var gateway: FakeGateway
    private lateinit var repository: FakeRepository
    private lateinit var listener: RecordingListener
    private lateinit var views: AttachmentViews
    private lateinit var scope: CoroutineScope
    private lateinit var engine: AttachmentEngine

    @Before fun setUp() {
        files = AttachmentFiles(folder.newFolder("files"), folder.newFolder("cache"))
        server = FakeBlobServer()
        gateway = FakeGateway(server)
        repository = FakeRepository()
        listener = RecordingListener()
        views = AttachmentViews()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = AttachmentEngine(scope, repository, files, gateway, views, listener)
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun outgoing(): Fixture = Fixture.sealed(files).also {
        repository.add(it.row(outgoing = true, stage = StoredStage.QUEUED))
        views.put(it.row(true, StoredStage.QUEUED).view())
    }

    private fun incoming(size: Int = 150_000, draft: MediaDraft = MediaDraft.file("report.pdf", "application/pdf")): Fixture =
        Fixture.remote(files, server, size, draft).also {
            repository.add(it.row(outgoing = false, stage = StoredStage.REMOTE))
            views.put(it.row(false, StoredStage.REMOTE).view())
        }

    @Test fun uploadSendsTheSealedBlobAndReleasesTheEnvelope() = runBlocking {
        val item = outgoing()
        assertEquals(AttachmentEngine.Outcome.DONE, engine.upload(item.messageId).await())
        assertArrayEquals(files.blob(item.blobId).readBytes(), server.blobs.getValue(item.blobId))
        assertEquals(StoredStage.UPLOADED, repository.stage(item.messageId))
        assertEquals(listOf(item.messageId), listener.uploaded)
        assertEquals(TransferStage.READY, views.get(item.messageId)!!.stage)
        assertEquals(1, gateway.requests.size)
        assertTrue(files.blob(item.blobId).isFile)
    }

    @Test fun offlineUploadWaitsAndStartsWhenTheConnectionReturns() = runBlocking {
        val item = outgoing()
        gateway.connected = false
        assertEquals(AttachmentEngine.Outcome.WAITING, engine.upload(item.messageId).await())
        assertEquals(StoredStage.QUEUED, repository.stage(item.messageId))
        assertTrue(gateway.requests.isEmpty())
        assertTrue(listener.uploaded.isEmpty())

        gateway.connected = true
        engine.onOnline { AutoFetchPolicy.Context(chatOpen = false, metered = false) }
        eventually { repository.stage(item.messageId) == StoredStage.UPLOADED }
        assertEquals(listOf(item.messageId), listener.uploaded)
    }

    @Test fun uploadBrokenHalfwayResumesFromTheServerOffsetWithoutDuplicates() = runBlocking {
        val item = outgoing()
        val total = files.blob(item.blobId).length()
        server.failNextPutAfter = 70_000
        assertEquals(AttachmentEngine.Outcome.DONE, engine.upload(item.messageId).await())
        assertEquals(listOf("", "bytes 70000-${total - 1}/$total"), server.puts.toList())
        assertEquals(2, gateway.requests.size)
        assertArrayEquals(files.blob(item.blobId).readBytes(), server.blobs.getValue(item.blobId))
        assertEquals(listOf(item.messageId), listener.uploaded)
    }

    @Test fun anExpiredTicketIsReplacedImmediatelyForUploadsAndDownloads() = runBlocking {
        val item = outgoing()
        gateway.staleTickets = 1
        assertEquals(AttachmentEngine.Outcome.DONE, engine.upload(item.messageId).await())
        assertEquals(2, gateway.requests.size)

        val remote = incoming(size = 20_000)
        gateway.staleTickets = 2
        assertEquals(AttachmentEngine.Outcome.DONE, engine.fetch(remote.messageId))
        assertEquals(StoredStage.READY, repository.stage(remote.messageId))
    }

    @Test fun ticketsThatKeepFailingEndInAFailedAttachmentNotALoop() = runBlocking {
        val item = outgoing()
        gateway.staleTickets = 99
        assertEquals(AttachmentEngine.Outcome.FAILED, engine.upload(item.messageId).await())
        assertEquals(1 + TransferPolicy.MAX_TICKET_RENEWALS, gateway.requests.size)
        assertEquals(StoredStage.FAILED, repository.stage(item.messageId))
    }

    @Test fun anAlreadyCompleteBlobIsNotSentTwice() = runBlocking {
        val item = outgoing()
        server.blobs[item.blobId] = files.blob(item.blobId).readBytes()
        assertEquals(AttachmentEngine.Outcome.DONE, engine.upload(item.messageId).await())
        assertTrue(server.puts.isEmpty())
        assertEquals(StoredStage.UPLOADED, repository.stage(item.messageId))
    }

    @Test fun concurrentUploadCallsShareOneTransfer() = runBlocking {
        val item = outgoing()
        gateway.ticketDelayMs = 200
        val first = engine.upload(item.messageId)
        val second = engine.upload(item.messageId)
        assertSame(first, second)
        assertEquals(AttachmentEngine.Outcome.DONE, first.await())
        assertEquals(1, server.puts.size)
        assertEquals(1, gateway.requests.size)
    }

    @Test fun aRefusedUploadFailsTheAttachmentAndTheMessageAndRetryWorks() = runBlocking {
        val item = outgoing()
        gateway.refuse = "blob_too_large"
        assertEquals(AttachmentEngine.Outcome.FAILED, engine.upload(item.messageId).await())
        assertEquals(StoredStage.FAILED, repository.stage(item.messageId))
        assertEquals(MessageStatus.FAILED, repository.status(item.messageId))
        assertEquals(TransferStage.FAILED, views.get(item.messageId)!!.stage)
        assertEquals(1, listener.messageChanges.get())
        assertTrue(files.blob(item.blobId).isFile)

        gateway.refuse = null
        assertTrue(repository.moveStage(item.messageId, StoredStage.QUEUED))
        assertEquals(MessageStatus.PENDING, repository.status(item.messageId))
        assertEquals(AttachmentEngine.Outcome.DONE, engine.upload(item.messageId).await())
        assertEquals(StoredStage.UPLOADED, repository.stage(item.messageId))
    }

    @Test fun aMissingLocalBlobFailsInsteadOfLoopingForever() = runBlocking {
        val item = outgoing()
        files.deleteBlob(item.blobId)
        assertEquals(AttachmentEngine.Outcome.FAILED, engine.upload(item.messageId).await())
        assertEquals(StoredStage.FAILED, repository.stage(item.messageId))
        assertTrue(gateway.requests.isEmpty())
    }

    @Test fun downloadVerifiesStoresAcknowledgesAndDecrypts() = runBlocking {
        val item = incoming()
        assertEquals(AttachmentEngine.Outcome.DONE, engine.fetch(item.messageId))
        assertEquals(StoredStage.READY, repository.stage(item.messageId))
        assertEquals(TransferStage.READY, views.get(item.messageId)!!.stage)
        assertEquals(listOf(item.blobId), gateway.acknowledged.toList())
        assertEquals(item.payload.size, files.blob(item.blobId).length())

        val plain = engine.plainFile(item.messageId)
        assertEquals("report.pdf", plain.name)
        assertEquals(File(files.sharedDir, item.messageId), plain.parentFile)
        assertArrayEquals(item.plain, plain.readBytes())
        assertEquals(plain, engine.plainFile(item.messageId))
    }

    @Test fun plainFileFetchesAnUnfetchedAttachmentFirst() = runBlocking {
        val item = incoming(size = 40_000)
        assertArrayEquals(item.plain, engine.plainFile(item.messageId).readBytes())
        assertEquals(StoredStage.READY, repository.stage(item.messageId))
    }

    @Test fun aTamperedBlobIsRejectedDeletedAndNotAcknowledged() = runBlocking {
        val item = incoming()
        val tampered = server.blobs.getValue(item.blobId).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        server.blobs[item.blobId] = tampered
        assertEquals(AttachmentEngine.Outcome.FAILED, engine.fetch(item.messageId))
        assertEquals(StoredStage.FAILED, repository.stage(item.messageId))
        assertFalse(files.blob(item.blobId).exists())
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test fun aBlobThatIsGoneOnTheServerExpiresAndCanBeRetried() = runBlocking {
        val item = incoming()
        val bytes = server.blobs.remove(item.blobId)!!
        assertEquals(AttachmentEngine.Outcome.EXPIRED, engine.fetch(item.messageId))
        assertEquals(StoredStage.EXPIRED, repository.stage(item.messageId))
        assertEquals(TransferStage.EXPIRED, views.get(item.messageId)!!.stage)

        server.blobs[item.blobId] = bytes
        assertTrue(repository.moveStage(item.messageId, StoredStage.REMOTE))
        assertEquals(AttachmentEngine.Outcome.DONE, engine.fetch(item.messageId))
    }

    @Test fun downloadResumesFromTheKeptPartialFile() = runBlocking {
        val item = incoming()
        val bytes = server.blobs.getValue(item.blobId)
        File(files.blob(item.blobId).path + BlobTransfer.PART_SUFFIX).also { it.parentFile!!.mkdirs() }.writeBytes(bytes.copyOf(60_000))
        assertEquals(AttachmentEngine.Outcome.DONE, engine.fetch(item.messageId))
        assertEquals(listOf("bytes=60000-"), server.gets.toList())
        assertArrayEquals(bytes, files.blob(item.blobId).readBytes())
    }

    @Test fun aTransientServerErrorIsRetriedWithTheSameDownload() = runBlocking {
        val item = incoming(size = 30_000)
        server.failGets = 1
        assertEquals(AttachmentEngine.Outcome.DONE, engine.fetch(item.messageId))
        assertEquals(2, server.gets.size)
    }

    @Test fun offlineFetchIsRememberedAndRunsAfterReconnect() = runBlocking {
        val item = incoming(size = 30_000)
        gateway.connected = false
        assertEquals(AttachmentEngine.Outcome.WAITING, engine.fetch(item.messageId))
        assertEquals(StoredStage.REMOTE, repository.stage(item.messageId))
        gateway.connected = true
        engine.onOnline { AutoFetchPolicy.Context(chatOpen = false, metered = true) }
        eventually { repository.stage(item.messageId) == StoredStage.READY }
    }

    @Test fun anAlreadyDownloadedBlobIsAdoptedAfterACrashWithoutNewTraffic() = runBlocking {
        val item = incoming(size = 30_000)
        val bytes = server.blobs.getValue(item.blobId)
        files.blobDir.mkdirs()
        files.blob(item.blobId).writeBytes(bytes)
        assertEquals(AttachmentEngine.Outcome.DONE, engine.fetch(item.messageId))
        assertTrue(gateway.requests.isEmpty())
        assertEquals(StoredStage.READY, repository.stage(item.messageId))
    }

    @Test fun autoFetchFollowsThePolicyPerAttachmentType() = runBlocking {
        val voice = incoming(30_000, MediaDraft.voice(5_000, intArrayOf(1000, 9000), "audio/mp4"))
        val photo = incoming(2 * 1024 * 1024, MediaDraft.image(800, 600, null, null))
        val file = incoming(30_000)

        engine.autoFetchConversation(PEER, AutoFetchPolicy.Context(chatOpen = true, metered = true))
        eventually { repository.stage(voice.messageId) == StoredStage.READY }
        assertEquals(StoredStage.REMOTE, repository.stage(photo.messageId))

        engine.autoFetchConversation(PEER, AutoFetchPolicy.Context(chatOpen = true, metered = false))
        eventually { repository.stage(photo.messageId) == StoredStage.READY }
        assertEquals(StoredStage.REMOTE, repository.stage(file.messageId))
    }

    @Test fun reconnectResumesUploadsAndLimitsBackgroundPrefetch() = runBlocking {
        val pending = List(3) { outgoing() }
        val remote = List(AutoFetchPolicy.BACKGROUND_BATCH + 4) { incoming(10_000, MediaDraft.voice(1_000, IntArray(4), "audio/mp4")) }
        engine.onOnline { AutoFetchPolicy.Context(chatOpen = false, metered = false) }
        eventually { pending.all { repository.stage(it.messageId) == StoredStage.UPLOADED } }
        eventually { remote.count { repository.stage(it.messageId) == StoredStage.READY } == AutoFetchPolicy.BACKGROUND_BATCH }
        delay(300)
        assertEquals(AutoFetchPolicy.BACKGROUND_BATCH, remote.count { repository.stage(it.messageId) == StoredStage.READY })
    }

    @Test fun cancelStopsAPendingTransferAndDiscardRemovesEveryFile() = runBlocking {
        val item = outgoing()
        gateway.ticketDelayMs = 5_000
        val job = engine.upload(item.messageId)
        eventually { engine.isActive(item.messageId) }
        engine.cancel(item.messageId)
        assertTrue(job.isCancelled)
        assertFalse(engine.isActive(item.messageId))
        assertTrue(server.puts.isEmpty())
        assertEquals(StoredStage.QUEUED, repository.stage(item.messageId))

        files.plainCopy(item.messageId, "report.pdf", files.blob(item.blobId), item.key, item.plain.size.toLong())
        assertTrue(File(files.sharedDir, item.messageId).exists())
        engine.discard(item.messageId, item.blobId, acknowledge = false)
        assertFalse(files.blob(item.blobId).exists())
        assertFalse(File(files.sharedDir, item.messageId).exists())
        assertEquals(null, views.get(item.messageId))
    }

    @Test fun discardOfAnIncomingAttachmentTellsTheServerItMayDropTheBlob() = runBlocking {
        val item = incoming(size = 10_000)
        engine.discard(item.messageId, item.blobId, acknowledge = true)
        assertEquals(listOf(item.blobId), gateway.acknowledged.toList())
    }

    @Test fun progressIsReportedWhileTransferringAndThrottled() = runBlocking {
        val item = Fixture.sealed(files, size = 3 * 1024 * 1024).also {
            repository.add(it.row(true, StoredStage.QUEUED))
            views.put(it.row(true, StoredStage.QUEUED).view())
        }
        val seen = java.util.Collections.synchronizedList(mutableListOf<Float>())
        val watcher = scope.launch {
            while (true) {
                engine.progressOf(item.messageId)?.let { seen += it }
                delay(2)
            }
        }
        engine.upload(item.messageId).await()
        watcher.cancel()
        assertTrue(seen.all { it in 0f..1f })
        assertNotNull(views.get(item.messageId))
        assertEquals(null, engine.progressOf(item.messageId))
        assertTrue(listener.attachmentChanges.get() >= 2)
    }
}
