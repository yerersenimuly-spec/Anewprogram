package app.line.ui.chat

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.line.R
import app.line.core.AttachmentPayload
import app.line.core.AttachmentView
import app.line.core.TransferStage
import app.line.crypto.ChatMessage
import app.line.ui.TextStyle
import app.line.ui.dp
import app.line.ui.label
import app.line.ui.roundRect
import kotlinx.coroutines.CoroutineScope
import java.io.File

sealed interface ChatItem { val id: Long }

data class DayItem(val dayKey: Long, val label: String) : ChatItem {
    override val id: Long get() = StableIds.day(dayKey)
}

/** A message with everything resolved for drawing; produced off the main thread. */
data class MsgItem(
    val message: ChatMessage,
    val attachment: AttachmentView?,
    val first: Boolean,
    val last: Boolean,
    val body: CharSequence?,
    val time: String,
    val description: String,
    val sizeText: String?,
) : ChatItem {
    override val id: Long get() = StableIds.of(message.id)
    val kind: ChatPreview.Kind
        get() = when (attachment?.payload?.type) {
            AttachmentPayload.Type.IMAGE -> ChatPreview.Kind.IMAGE
            AttachmentPayload.Type.VOICE -> ChatPreview.Kind.VOICE
            AttachmentPayload.Type.FILE -> ChatPreview.Kind.FILE
            null -> ChatPreview.Kind.TEXT
        }
}

interface MessageCallbacks {
    fun onTap(item: MsgItem)
    fun onLongPress(item: MsgItem)
    fun onLink(url: String)
    fun onSeek(item: MsgItem, fraction: Float)
    fun onSpeed()
    fun onBound(item: MsgItem)
    fun playback(id: String): Playback?
    suspend fun plainFile(id: String): File
}

class MessageAdapter(
    private val scope: CoroutineScope,
    private val format: ChatFormat,
    private val callbacks: MessageCallbacks,
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(Diff) {

    class DayHolder(val text: TextView, container: FrameLayout) : RecyclerView.ViewHolder(container)

    class MsgHolder(
        container: FrameLayout,
        val bubble: BubbleView,
        val photo: PhotoContent?,
        val voice: VoiceContent?,
        val file: FileContent?,
    ) : RecyclerView.ViewHolder(container) {
        var item: MsgItem? = null
    }

    private var availableWidth = 0

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is DayItem -> DAY
        is MsgItem -> when (item.kind) {
            ChatPreview.Kind.TEXT -> TEXT
            ChatPreview.Kind.IMAGE -> PHOTO
            ChatPreview.Kind.VOICE -> VOICE
            ChatPreview.Kind.FILE -> FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        availableWidth = parent.width
        val container = FrameLayout(context)
        container.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (viewType == DAY) {
            val text = context.label("", TextStyle.CAPTION_STRONG, R.color.text_secondary).apply {
                background = context.roundRect(R.color.surface_raised, 12)
                setPadding(context.dp(12), context.dp(5), context.dp(12), context.dp(5))
            }
            container.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = context.dp(14); bottomMargin = context.dp(6) })
            return DayHolder(text, container)
        }
        container.setPadding(context.dp(12), 0, context.dp(12), 0)
        val bubble = BubbleView(context)
        container.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var photo: PhotoContent? = null
        var voice: VoiceContent? = null
        var file: FileContent? = null
        when (viewType) {
            PHOTO -> photo = PhotoContent(context, scope).also { bubble.media.addView(it) }
            VOICE -> voice = VoiceContent(context, format).also { bubble.media.addView(it) }
            FILE -> file = FileContent(context).also { bubble.media.addView(it) }
        }
        bubble.showMedia(viewType != TEXT)
        val holder = MsgHolder(container, bubble, photo, voice, file)
        bubble.listener = object : BubbleListener {
            override fun onTap() { holder.item?.let(callbacks::onTap) }
            override fun onLongPress() { holder.item?.let(callbacks::onLongPress) }
            override fun onLink(url: String) = callbacks.onLink(url)
        }
        voice?.play?.setOnClickListener { holder.item?.let(callbacks::onTap) }
        voice?.wave?.onSeek = { fraction -> holder.item?.let { callbacks.onSeek(it, fraction) } }
        voice?.wave?.onLongPress = { holder.item?.let(callbacks::onLongPress) }
        voice?.speed?.setOnClickListener { callbacks.onSpeed() }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DayItem -> (holder as DayHolder).text.text = item.label
            is MsgItem -> bind(holder as MsgHolder, item)
        }
    }

    private fun bind(holder: MsgHolder, item: MsgItem) {
        holder.item = item
        val message = item.message
        val context = holder.itemView.context
        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (message.outgoing) Gravity.END else Gravity.START
        params.topMargin = context.dp(if (item.first) 10 else 2)
        holder.bubble.layoutParams = params
        holder.bubble.bindShape(message.outgoing, item.first)
        holder.bubble.contentDescription = item.description
        holder.bubble.bindBody(item.body)
        val status = Delivery.marker(message.outgoing, message.state)
        val attachment = item.attachment
        val noteText = when {
            attachment == null -> null
            attachment.stage == TransferStage.FAILED -> context.getString(if (message.outgoing) R.string.status_failed else R.string.media_failed)
            attachment.stage == TransferStage.EXPIRED -> context.getString(R.string.cv_media_expired)
            else -> null
        }
        when {
            holder.photo != null && attachment != null -> {
                val payload = attachment.payload
                val maxWidth = minOf((availableWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) * 70 / 100, context.dp(300))
                val box = ImageSizing.box(payload.width?.toInt(), payload.height?.toInt(), maxWidth, context.dp(340), context.dp(120))
                holder.photo.bind(PhotoModel(message.id, box, payload.thumb, attachment.stage, attachment.progress, attachment.outgoing) { callbacks.plainFile(message.id) }, noteText)
                holder.bubble.bindMeta(item.time, status, if (item.body.isNullOrEmpty()) BubbleView.MetaMode.PILL else BubbleView.MetaMode.INLINE)
            }
            holder.voice != null && attachment != null -> {
                val payload = attachment.payload
                holder.voice.bind(VoiceModel(message.id, payload.waveform, payload.durationMs ?: 0L, attachment.outgoing, attachment.played, attachment.stage, attachment.progress),
                    callbacks.playback(message.id))
                holder.bubble.bindMeta(item.time, status, BubbleView.MetaMode.PLAIN)
            }
            holder.file != null && attachment != null -> {
                val payload = attachment.payload
                holder.file.bind(attachment.outgoing, payload.name ?: context.getString(R.string.preview_file), payload.mime, item.sizeText.orEmpty(), attachment.stage, attachment.progress)
                holder.bubble.bindMeta(item.time, status, BubbleView.MetaMode.PLAIN)
            }
            else -> holder.bubble.bindMeta(item.time, status, BubbleView.MetaMode.INLINE)
        }
        callbacks.onBound(item)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MsgHolder) {
            holder.photo?.recycle()
            holder.bubble.clearTransient()
            holder.item = null
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(a: ChatItem, b: ChatItem) = a.id == b.id
        override fun areContentsTheSame(a: ChatItem, b: ChatItem) = a == b
    }

    private companion object {
        const val DAY = 0
        const val TEXT = 1
        const val PHOTO = 2
        const val VOICE = 3
        const val FILE = 4
    }
}
