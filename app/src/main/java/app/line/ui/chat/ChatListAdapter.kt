package app.line.ui.chat

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.line.R
import app.line.core.MessageStatus
import app.line.ui.AvatarView
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.StatusTicks
import app.line.ui.TextStyle
import app.line.ui.color
import app.line.ui.dp
import app.line.ui.label
import app.line.ui.ripple
import app.line.ui.roundRect

/** One dialog in the chat list, fully resolved so binding only assigns. */
data class ChatRow(
    val peer: String,
    val title: String,
    val name: String?,
    val preview: String,
    val previewKind: ChatPreview.Kind,
    val stamp: String,
    val unread: Int,
    val status: MessageStatus?,
    val description: String,
)

class ChatRowView(context: Context) : LinearLayout(context) {
    private val avatar = AvatarView(context)
    private val title = context.label("", TextStyle.SUBTITLE, maxLines = 1)
    private val stamp = context.label("", TextStyle.CAPTION, R.color.text_tertiary, maxLines = 1)
    private val ticks = StatusTicks(context)
    private val kindIcon = IconView(context, "image", R.color.text_tertiary)
    private val preview = context.label("", TextStyle.CALLOUT, R.color.text_secondary, maxLines = 1)
    private val badge = context.label("", TextStyle.MICRO, R.color.on_accent).apply {
        gravity = Gravity.CENTER
        background = context.roundRect(R.color.accent, Dimens.RADIUS_S)
        setPadding(dp(4), dp(2), dp(4), dp(2))
        minWidth = dp(20)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Dimens.SCREEN_PADDING), dp(12), dp(Dimens.SCREEN_PADDING), dp(12))
        background = context.ripple(null)
        isClickable = true
        isFocusable = true
        addView(avatar, LayoutParams(dp(52), dp(52)))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ticks, LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) })
        top.addView(stamp, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(2) })
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(kindIcon, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
        bottom.addView(preview, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        column.addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
    }

    fun bind(row: ChatRow) {
        avatar.bind(row.peer, row.name)
        title.text = row.title
        stamp.text = row.stamp
        stamp.setTextColor(context.color(if (row.unread > 0) R.color.accent else R.color.text_tertiary))
        if (row.status != null) { ticks.visibility = View.VISIBLE; ticks.bind(row.status, onBubble = false) } else ticks.visibility = View.GONE
        val icon = when (row.previewKind) {
            ChatPreview.Kind.IMAGE -> "image"
            ChatPreview.Kind.VOICE -> "mic"
            ChatPreview.Kind.FILE -> "file"
            ChatPreview.Kind.TEXT -> null
        }
        if (icon != null) { kindIcon.visibility = View.VISIBLE; kindIcon.setIcon(icon) } else kindIcon.visibility = View.GONE
        preview.text = row.preview
        preview.setTextColor(context.color(if (row.unread > 0) R.color.text_primary else R.color.text_secondary))
        if (row.unread > 0) { badge.visibility = View.VISIBLE; badge.text = if (row.unread > 99) "99+" else row.unread.toString() } else badge.visibility = View.GONE
        contentDescription = row.description
    }
}

class ChatListAdapter(
    private val onOpen: (ChatRow) -> Unit,
    private val onMenu: (ChatRow) -> Unit,
) : ListAdapter<ChatRow, ChatListAdapter.Holder>(Diff) {
    class Holder(val row: ChatRowView) : RecyclerView.ViewHolder(row)

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = StableIds.of(getItem(position).peer)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = ChatRowView(parent.context)
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val holder = Holder(view)
        view.setOnClickListener { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onOpen(getItem(it)) } }
        view.setOnLongClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { position -> onMenu(getItem(position)) }
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.row.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<ChatRow>() {
        override fun areItemsTheSame(a: ChatRow, b: ChatRow) = a.peer == b.peer
        override fun areContentsTheSame(a: ChatRow, b: ChatRow) = a == b
    }
}
