package app.line.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredStageTest {
    @Test fun outgoingRowsMoveQueuedToUploadedOrFailedAndBack() {
        assertEquals(
            setOf(StoredStage.QUEUED to StoredStage.UPLOADED, StoredStage.QUEUED to StoredStage.FAILED, StoredStage.FAILED to StoredStage.QUEUED),
            pairs(outgoing = true),
        )
    }

    @Test fun incomingRowsMoveRemoteToReadyFailedOrExpiredAndBackToRemote() {
        assertEquals(
            setOf(
                StoredStage.REMOTE to StoredStage.READY, StoredStage.REMOTE to StoredStage.FAILED, StoredStage.REMOTE to StoredStage.EXPIRED,
                StoredStage.READY to StoredStage.REMOTE, StoredStage.FAILED to StoredStage.REMOTE, StoredStage.EXPIRED to StoredStage.REMOTE,
            ),
            pairs(outgoing = false),
        )
    }

    @Test fun anUploadedEnvelopeStageCanNeverBeUndone() {
        StoredStage.entries.forEach { assertFalse(StoredStage.allowed(true, StoredStage.UPLOADED, it)) }
    }

    @Test fun wireNamesMatchTheOutboxQueryAndRoundTrip() {
        assertEquals("uploaded", StoredStage.UPLOADED.wire)
        StoredStage.entries.forEach { assertEquals(it, StoredStage.parse(it.wire)) }
        assertNull(StoredStage.parse("uploading"))
        assertNull(StoredStage.parse(null))
        assertEquals(StoredStage.QUEUED, StoredStage.initial(true))
        assertEquals(StoredStage.REMOTE, StoredStage.initial(false))
    }

    @Test fun transferStagesShowTheActiveTransferOnlyForPendingRows() {
        assertEquals(TransferStage.TRANSFERRING, StoredStage.QUEUED.toTransferStage(true))
        assertEquals(TransferStage.QUEUED, StoredStage.QUEUED.toTransferStage(false))
        assertEquals(TransferStage.TRANSFERRING, StoredStage.REMOTE.toTransferStage(true))
        assertEquals(TransferStage.READY, StoredStage.UPLOADED.toTransferStage(true))
        assertEquals(TransferStage.READY, StoredStage.READY.toTransferStage(false))
        assertEquals(TransferStage.FAILED, StoredStage.FAILED.toTransferStage(true))
        assertEquals(TransferStage.EXPIRED, StoredStage.EXPIRED.toTransferStage(false))
    }

    private fun pairs(outgoing: Boolean) = StoredStage.entries.flatMap { from -> StoredStage.entries.map { from to it } }
        .filter { (from, to) -> StoredStage.allowed(outgoing, from, to) }.toSet()
}

class AttachmentViewsTest {
    private val payload = AttachmentPayload(
        AttachmentPayload.Type.VOICE, "7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d", ByteArray(32), AttachmentCrypto.encryptedSize(10), 10,
        "audio/mp4", null, null, null, 1000, null, null, null,
    )

    private fun view(id: String, stage: TransferStage = TransferStage.QUEUED) = AttachmentView(id, payload, stage, 0f, false, false)

    @Test fun progressIsOnlyShownWhileTransferring() {
        assertEquals(0.4f, AttachmentViews.build("a", payload, false, StoredStage.REMOTE, false, null, true, 0.4f).progress, 0f)
        assertEquals(0f, AttachmentViews.build("a", payload, false, StoredStage.REMOTE, false, null, false, 0.4f).progress, 0f)
        assertEquals(1f, AttachmentViews.build("a", payload, false, StoredStage.READY, false, null, false, 0f).progress, 0f)
        assertEquals(1f, AttachmentViews.build("a", payload, false, StoredStage.REMOTE, false, null, true, 7f).progress, 0f)
    }

    @Test fun aMessageWithoutARowFallsBackToItsStatus() {
        assertEquals(TransferStage.FAILED, AttachmentViews.build("a", payload, true, null, false, MessageStatus.FAILED).stage)
        assertEquals(TransferStage.QUEUED, AttachmentViews.build("a", payload, true, null, false, MessageStatus.PENDING).stage)
        assertEquals(TransferStage.QUEUED, AttachmentViews.build("a", payload, false, null, false, MessageStatus.FAILED).stage)
    }

    @Test fun theCacheIsBoundedAndKeepsRecentlyUsedViews() {
        val views = AttachmentViews(capacity = 3)
        listOf("a", "b", "c").forEach { views.put(view(it)) }
        views.get("a")
        views.put(view("d"))
        assertNotNull(views.get("a"))
        assertNull(views.get("b"))
    }

    @Test fun updateOnlyTouchesCachedViewsAndAGenerationGuardsStaleLoads() {
        val views = AttachmentViews()
        assertNull(views.update("missing") { it })
        views.put(view("a"))
        val generation = views.generation()
        views.update("a") { it.copy(stage = TransferStage.TRANSFERRING, progress = 0.5f) }
        assertFalse(views.putAllIfUnchanged(listOf(view("b")), generation))
        assertNull(views.get("b"))
        assertTrue(views.putAllIfUnchanged(listOf(view("b")), views.generation()))
        assertNotNull(views.get("b"))
        assertEquals(TransferStage.TRANSFERRING, views.get("a")!!.stage)
        views.remove("a")
        assertNull(views.get("a"))
    }
}
