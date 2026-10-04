package app.line.ui.chat

import android.content.Context
import android.text.SpannableString
import android.text.util.Linkify
import android.util.LruCache
import app.line.R
import app.line.core.AttachmentView
import app.line.core.MessageStatus
import app.line.crypto.ChatMessage

/** Turns stored messages into the items a conversation list draws. Safe to run off the main thread. */
class ChatItemsBuilder(private val context: Context, private val format: ChatFormat) {
    private val links = LruCache<String, CharSequence>(400)

    fun build(messages: List<ChatMessage>, attachments: Map<String, AttachmentView?>, now: Long): List<ChatItem> {
        val slots = BubbleGrouping.layout(messages.map { BubbleGrouping.Meta(it.outgoing, it.createdAt) }, format.zone)
        val items = ArrayList<ChatItem>(slots.size)
        for (slot in slots) {
            when (slot) {
                is BubbleGrouping.Slot.Day -> items += DayItem(slot.key, format.separator(slot.at, now))
                is BubbleGrouping.Slot.Message -> items += item(messages[slot.index], attachments[messages[slot.index].id], slot.first, slot.last)
            }
        }
        return items
    }

    private fun item(message: ChatMessage, attachment: AttachmentView?, first: Boolean, last: Boolean): MsgItem {
        val body: CharSequence? = when {
            message.kind == "text" -> linked(message.id, message.text)
            attachment != null -> attachment.payload.caption?.takeIf { it.isNotBlank() }?.let { linked(message.id, it) }
            else -> format.kindLabel(ChatPreview.kindOf(message.kind))
        }
        val time = format.time(message.createdAt)
        val spoken = when {
            attachment != null -> format.kindLabel(when (attachment.payload.type) {
                app.line.core.AttachmentPayload.Type.IMAGE -> ChatPreview.Kind.IMAGE
                app.line.core.AttachmentPayload.Type.VOICE -> ChatPreview.Kind.VOICE
                app.line.core.AttachmentPayload.Type.FILE -> ChatPreview.Kind.FILE
            }) + (body?.let { ", $it" } ?: "")
            else -> body?.toString().orEmpty()
        }
        val status = if (message.outgoing) ", " + context.getString(statusText(message.state)) else ""
        val sizeText = attachment?.payload?.takeIf { it.type == app.line.core.AttachmentPayload.Type.FILE }?.let { format.size(it.plainSize) }
        return MsgItem(message, attachment, first, last, body, time, "$spoken, $time$status", sizeText)
    }

    private fun linked(id: String, text: String): CharSequence = links.get(id)?.takeIf { it.toString() == text } ?: run {
        val result: CharSequence = if (text.indexOf('.') >= 0 || text.indexOf('@') >= 0 || text.indexOf(':') >= 0) {
            SpannableString(text).also { Linkify.addLinks(it, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES) }
        } else text
        links.put(id, result)
        result
    }

    companion object {
        fun statusText(status: MessageStatus): Int = when (status) {
            MessageStatus.PENDING -> R.string.status_pending
            MessageStatus.SENT, MessageStatus.RECEIVED -> R.string.status_sent
            MessageStatus.DELIVERED -> R.string.status_delivered
            MessageStatus.READ -> R.string.status_read
            MessageStatus.FAILED -> R.string.status_failed
        }
    }
}
