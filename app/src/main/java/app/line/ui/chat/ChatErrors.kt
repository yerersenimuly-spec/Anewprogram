package app.line.ui.chat

import android.content.Context
import app.line.R
import app.line.media.attachments.AttachmentPrepareException
import app.line.media.attachments.VoiceRecorderException
import app.line.ui.errorText

/** Friendly text for anything that can go wrong in chats, including media preparation and recording. */
fun Context.chatErrorText(error: Throwable): String = when (error) {
    is AttachmentPrepareException -> getString(
        when (error.reason) {
            AttachmentPrepareException.Reason.UNREADABLE -> R.string.cv_media_unreadable
            AttachmentPrepareException.Reason.UNSUPPORTED -> R.string.media_unsupported_image
            AttachmentPrepareException.Reason.TOO_LARGE -> R.string.media_too_large
            AttachmentPrepareException.Reason.OUT_OF_MEMORY -> R.string.cv_media_memory
        },
    )
    is VoiceRecorderException -> getString(
        when (error.reason) {
            VoiceRecorderException.Reason.PERMISSION -> R.string.err_mic_required
            VoiceRecorderException.Reason.BUSY -> R.string.cv_voice_busy
            else -> R.string.err_generic
        },
    )
    else -> errorText(error)
}
