package app.line.ui.chat

import app.line.core.MessageStatus
import app.line.core.TransferStage
import app.line.ui.chat.ChatPreview.Kind
import app.line.ui.chat.TransferUi.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferUiTest {
    @Test fun ringIsDeterminateOnlyWhileTransferring() {
        assertEquals(0.4f, TransferUi.ringProgress(TransferStage.TRANSFERRING, 0.4f)!!, 0f)
        assertEquals(1f, TransferUi.ringProgress(TransferStage.TRANSFERRING, 7f)!!, 0f)
        assertNull(TransferUi.ringProgress(TransferStage.QUEUED, 0.4f))
        assertTrue(TransferUi.showsRing(TransferStage.QUEUED))
        assertFalse(TransferUi.showsRing(TransferStage.READY))
    }

    @Test fun onlyIncomingImagesAndVoiceDownloadOnTheirOwn() {
        assertTrue(TransferUi.autoFetch(false, Kind.IMAGE, TransferStage.QUEUED))
        assertTrue(TransferUi.autoFetch(false, Kind.VOICE, TransferStage.QUEUED))
        assertFalse(TransferUi.autoFetch(false, Kind.FILE, TransferStage.QUEUED))
        assertFalse(TransferUi.autoFetch(true, Kind.IMAGE, TransferStage.QUEUED))
        assertFalse(TransferUi.autoFetch(false, Kind.IMAGE, TransferStage.FAILED))
    }

    @Test fun incomingTapsFollowTheStage() {
        assertEquals(Tap.OPEN, TransferUi.tap(false, TransferStage.READY, MessageStatus.RECEIVED))
        assertEquals(Tap.FETCH, TransferUi.tap(false, TransferStage.QUEUED, MessageStatus.RECEIVED))
        assertEquals(Tap.NONE, TransferUi.tap(false, TransferStage.TRANSFERRING, MessageStatus.RECEIVED))
        assertEquals(Tap.RETRY_ATTACHMENT, TransferUi.tap(false, TransferStage.FAILED, MessageStatus.RECEIVED))
        assertEquals(Tap.EXPIRED, TransferUi.tap(false, TransferStage.EXPIRED, MessageStatus.RECEIVED))
    }

    @Test fun outgoingTapsRetryWhatFailedAndOfferDeletionWhileInFlight() {
        assertEquals(Tap.MENU, TransferUi.tap(true, TransferStage.TRANSFERRING, MessageStatus.PENDING))
        assertEquals(Tap.MENU, TransferUi.tap(true, TransferStage.QUEUED, MessageStatus.PENDING))
        assertEquals(Tap.RETRY_ATTACHMENT, TransferUi.tap(true, TransferStage.FAILED, MessageStatus.FAILED))
        assertEquals(Tap.RETRY_MESSAGE, TransferUi.tap(true, TransferStage.READY, MessageStatus.FAILED))
        assertEquals(Tap.OPEN, TransferUi.tap(true, TransferStage.READY, MessageStatus.DELIVERED))
    }

    @Test fun brokenStagesAreFailedAndExpired() {
        assertTrue(TransferUi.isBroken(TransferStage.FAILED))
        assertTrue(TransferUi.isBroken(TransferStage.EXPIRED))
        assertFalse(TransferUi.isBroken(TransferStage.QUEUED))
    }
}
