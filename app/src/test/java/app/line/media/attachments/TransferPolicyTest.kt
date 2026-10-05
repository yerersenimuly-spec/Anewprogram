package app.line.media.attachments

import app.line.core.AttachmentCorruptException
import app.line.core.AttachmentTooLarge
import app.line.core.AttachmentUnreadable
import app.line.core.AttachmentUnsupported
import app.line.core.MediaLimits
import app.line.core.MediaUnavailable
import app.line.media.attachments.TransferDirection.DOWNLOAD
import app.line.media.attachments.TransferDirection.UPLOAD
import app.line.media.attachments.TransferPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class TransferPolicyTest {
    private fun decide(direction: TransferDirection, error: Throwable, attempt: Int = 0, renewals: Int = 0) =
        TransferPolicy.decide(direction, error, attempt, renewals)

    @Test fun networkAndServerErrorsBackOffThenWaitInsteadOfFailing() {
        for (attempt in 0 until TransferPolicy.MAX_ATTEMPTS) {
            val retry = decide(UPLOAD, BlobException.Network(), attempt) as Decision.Retry
            assertEquals(false, retry.newTicket)
            if (attempt > 0) assertTrue(retry.delayMs > (decide(UPLOAD, BlobException.Network(), attempt - 1) as Decision.Retry).delayMs)
        }
        assertEquals(Decision.Wait(TransferPolicy.IDLE_RETRY_MS), decide(UPLOAD, BlobException.Network(), TransferPolicy.MAX_ATTEMPTS))
        assertTrue(decide(DOWNLOAD, BlobException.Server(503)) is Decision.Retry)
        assertEquals(Decision.Fail(false), decide(DOWNLOAD, BlobException.Server(400)))
        assertTrue(decide(UPLOAD, IOException("disk")) is Decision.Retry)
    }

    @Test fun rejectedTicketsAreRenewedAThreeTimesAtMost() {
        listOf(BlobException.Unauthorized(), BlobException.Conflict()).forEach { error ->
            assertEquals(Decision.Retry(0, true), decide(UPLOAD, error))
            assertEquals(Decision.Retry(0, true), decide(DOWNLOAD, error, renewals = TransferPolicy.MAX_TICKET_RENEWALS - 1))
            assertEquals(Decision.Fail(false), decide(UPLOAD, error, renewals = TransferPolicy.MAX_TICKET_RENEWALS))
        }
    }

    @Test fun anUploadStateConflictWaitsForTheServerToLetGoInsteadOfFailing() {
        val conflict = BlobException.Conflict(serverOffset = 4096)
        val delays = (0 until TransferPolicy.MAX_ATTEMPTS).map { (decide(UPLOAD, conflict, attempt = it) as Decision.Retry).also { retry -> assertTrue(retry.newTicket) }.delayMs }
        assertEquals(delays.sorted(), delays)
        assertTrue(delays.sum() >= 30_000)
        assertEquals(Decision.Wait(TransferPolicy.IDLE_RETRY_MS), decide(UPLOAD, conflict, attempt = TransferPolicy.MAX_ATTEMPTS, renewals = 99))
    }

    @Test fun aMissingBlobMeansExpiredForDownloadsAndANewUploadForUploads() {
        assertEquals(Decision.Fail(true), decide(DOWNLOAD, BlobException.NotFound()))
        assertEquals(Decision.Fail(true), decide(DOWNLOAD, BlobException.Server(410)))
        assertEquals(Decision.Retry(0, true), decide(UPLOAD, BlobException.NotFound()))
    }

    @Test fun limitsAndQuotaAreFinal() {
        assertEquals(Decision.Fail(false), decide(UPLOAD, BlobException.TooLarge()))
        assertEquals(Decision.Fail(false), decide(UPLOAD, BlobException.Quota()))
    }

    @Test fun rateLimitsHonourRetryAfterWithinBounds() {
        assertEquals(Decision.Retry(7_000, false), decide(UPLOAD, BlobException.RateLimited(7)))
        assertEquals(Decision.Retry(60_000, false), decide(UPLOAD, BlobException.RateLimited(3_600)))
        assertEquals(Decision.Retry(5_000, false), decide(UPLOAD, BlobException.RateLimited(null)))
    }

    @Test fun serverErrorCodesOnTicketsAreClassified() {
        assertTrue(decide(UPLOAD, TicketRefused("rate_limited")) is Decision.Retry)
        assertTrue(decide(UPLOAD, TicketRefused("storage_unavailable")) is Decision.Retry)
        assertEquals(Decision.Wait(TransferPolicy.CHAT_DISABLED_RETRY_MS), decide(UPLOAD, TicketRefused("chat_disabled")))
        listOf("blob_too_large", "blocked", "not_found", "id_conflict", "blob_quota", "invalid_message").forEach {
            assertEquals(it, Decision.Fail(false), decide(UPLOAD, TicketRefused(it)))
        }
        assertEquals(Decision.Fail(true), decide(DOWNLOAD, TicketRefused("not_found")))
        assertEquals(Decision.Fail(true), decide(DOWNLOAD, TicketRefused("expired")))
        assertEquals(Decision.Fail(false), decide(DOWNLOAD, TicketRefused("unauthorized")))
        assertEquals(Decision.Wait(TransferPolicy.IDLE_RETRY_MS), decide(UPLOAD, TicketRefused("something_new")))
    }

    @Test fun corruptionAndMissingFilesAreFinalButUnknownBugsDoNotLoop() {
        assertEquals(Decision.Fail(false), decide(DOWNLOAD, AttachmentCorruptException("bad")))
        assertEquals(Decision.Fail(false), decide(UPLOAD, FileNotFoundException()))
        assertEquals(Decision.Fail(false), decide(UPLOAD, IllegalStateException()))
    }

    @Test fun blobExceptionsDescribeThemselvesForTheEngine() {
        assertTrue(BlobException.Network().retryable)
        assertTrue(BlobException.Unauthorized().needsNewTicket)
        assertTrue(!BlobException.NotFound().retryable)
    }
}

class PrepareExceptionMappingTest {
    @Test fun everyReasonMapsToATypedSendException() {
        fun map(reason: AttachmentPrepareException.Reason) = AttachmentPrepareException(reason).toSendException()
        assertTrue(map(AttachmentPrepareException.Reason.UNREADABLE) is AttachmentUnreadable)
        assertTrue(map(AttachmentPrepareException.Reason.UNSUPPORTED) is AttachmentUnsupported)
        assertTrue(map(AttachmentPrepareException.Reason.OUT_OF_MEMORY) is MediaUnavailable)
        val tooLarge = map(AttachmentPrepareException.Reason.TOO_LARGE) as AttachmentTooLarge
        assertEquals(MediaLimits.MAX_FILE_BYTES, tooLarge.limit)
    }

    @Test fun theSweptOutgoingDirectoryIsTheOneTheImageProcessorWritesTo() {
        assertEquals(ImageProcessor.OUTGOING_DIR, AttachmentFiles.OUTGOING)
        assertEquals(ImageProcessor.MAX_FILE_BYTES, MediaLimits.MAX_FILE_BYTES)
    }
}
