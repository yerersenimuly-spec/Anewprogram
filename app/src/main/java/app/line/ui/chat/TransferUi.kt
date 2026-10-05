package app.line.ui.chat

import app.line.core.MessageStatus
import app.line.core.TransferStage

/** How an attachment bubble reacts to its transfer state; shared by photo, voice and file bubbles. */
object TransferUi {
    enum class Tap {
        /** Image: viewer. Voice: play or pause. File: open with another app. */
        OPEN,

        /** Incoming attachment that was not fetched yet. */
        FETCH,

        /** Failed transfer or send: try again. */
        RETRY_ATTACHMENT,
        RETRY_MESSAGE,

        /** Own attachment still on its way: offer to delete it. */
        MENU,

        /** The sender's copy is gone from the server. */
        EXPIRED,
        NONE,
    }

    /** Progress for the ring: 0..1 while transferring, null for an indeterminate spinner. */
    fun ringProgress(stage: TransferStage, progress: Float): Float? =
        if (stage == TransferStage.TRANSFERRING) progress.coerceIn(0f, 1f) else null

    fun showsRing(stage: TransferStage): Boolean = stage == TransferStage.QUEUED || stage == TransferStage.TRANSFERRING

    fun isBroken(stage: TransferStage): Boolean = stage == TransferStage.FAILED || stage == TransferStage.EXPIRED

    /** Images and voice messages download by themselves while the chat is open; files wait for a tap. */
    fun autoFetch(outgoing: Boolean, kind: ChatPreview.Kind, stage: TransferStage): Boolean =
        !outgoing && stage == TransferStage.QUEUED && (kind == ChatPreview.Kind.IMAGE || kind == ChatPreview.Kind.VOICE)

    fun tap(outgoing: Boolean, stage: TransferStage, messageStatus: MessageStatus): Tap = when {
        outgoing -> when (stage) {
            TransferStage.FAILED, TransferStage.EXPIRED -> Tap.RETRY_ATTACHMENT
            TransferStage.QUEUED, TransferStage.TRANSFERRING -> Tap.MENU
            TransferStage.READY -> if (messageStatus == MessageStatus.FAILED) Tap.RETRY_MESSAGE else Tap.OPEN
        }
        else -> when (stage) {
            TransferStage.READY -> Tap.OPEN
            TransferStage.QUEUED -> Tap.FETCH
            TransferStage.TRANSFERRING -> Tap.NONE
            TransferStage.FAILED -> Tap.RETRY_ATTACHMENT
            TransferStage.EXPIRED -> Tap.EXPIRED
        }
    }
}
