package app.line.ui.calls

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.line.R
import app.line.ui.*
import java.util.Date
import java.util.Locale

/** Recent calls: loading, empty and error states around a paginated list. */
class RecentsView(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {
    interface Callbacks {
        fun nameOf(number: String): PeerLabel
        fun open(row: RecentRow)
        fun more(row: RecentRow)
        fun loadMore()
        fun retry()
        fun showKeypad()
    }

    private val adapter = Adapter()
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@RecentsView.adapter
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(0, 0, 0, dp(16))
        itemAnimator = null
    }
    private val skeleton = SkeletonList(context)
    private var overlay: View? = null

    init {
        addView(list, LayoutParams(MATCH, MATCH))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val manager = view.layoutManager as LinearLayoutManager
                if (dy > 0 && manager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_AHEAD) callbacks.loadMore()
            }
        })
    }

    fun showLoading() = overlay(skeleton, listVisible = false)

    fun showItems(items: List<RecentItem>) {
        overlay(null, listVisible = true)
        adapter.submit(items)
    }

    fun showEmpty() = overlay(
        context.emptyState("phone", context.getString(R.string.cp_recents_empty_title), context.getString(R.string.cp_recents_empty_body),
            context.getString(R.string.cp_recents_empty_action)) { callbacks.showKeypad() },
        listVisible = false,
    )

    fun showError() = overlay(
        context.emptyState("alert", context.getString(R.string.cp_recents_error_title), null, context.getString(R.string.cp_retry)) { callbacks.retry() },
        listVisible = false,
    )

    /** Names may arrive after the list (contact edits, profile sync): rebind without reloading. */
    fun refreshNames() = adapter.notifyDataSetChanged()

    val hasItems: Boolean get() = adapter.itemCount > 0

    private fun overlay(view: View?, listVisible: Boolean) {
        if (overlay === view && list.visibility == if (listVisible) VISIBLE else GONE) return
        overlay?.let { removeView(it) }
        overlay = view
        list.visibility = if (listVisible) VISIBLE else GONE
        if (view != null) {
            addView(view, if (view is SkeletonList) LayoutParams(MATCH, WRAP) else LayoutParams(MATCH, MATCH))
            Motion.enter(view, 140)
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<RecentItem> = emptyList()
        private val clock = DateFormat.getTimeFormat(context)
        private val dateFormat by lazy { dayFormat(false) }
        private val dateWithYear by lazy { dayFormat(true) }
        private val date = Date()
        private val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

        private fun dayFormat(year: Boolean) = java.text.SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), if (year) "dMMMMy" else "dMMMM"), Locale.getDefault(),
        )

        fun submit(next: List<RecentItem>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = idOf(old[a]) == idOf(next[b])
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
            })
            items = next
            diff.dispatchUpdatesTo(this)
        }

        private fun idOf(item: RecentItem): String = when (item) {
            is RecentItem.Header -> "h${item.epochDay}"
            is RecentItem.Row -> "r${item.row.key}"
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is RecentItem.Header) HEADER else ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == HEADER) context.label("", TextStyle.MICRO, R.color.text_tertiary).apply {
                setPadding(dp(Dimens.SCREEN_PADDING), dp(20), dp(Dimens.SCREEN_PADDING), dp(6))
            } else RecentRowView(context)
            view.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is RecentItem.Header -> (holder.itemView as TextView).text = headerText(item)
                is RecentItem.Row -> {
                    date.time = item.row.latest.timestamp
                    (holder.itemView as RecentRowView).bind(item.row, callbacks, clock.format(date))
                }
            }
        }

        private fun headerText(item: RecentItem.Header): String = when (item.label) {
            DayLabel.TODAY -> context.getString(R.string.cp_today)
            DayLabel.YESTERDAY -> context.getString(R.string.cp_yesterday)
            DayLabel.OTHER -> {
                date.time = item.timestamp
                val year = java.util.Calendar.getInstance().apply { time = date }.get(java.util.Calendar.YEAR)
                (if (year == thisYear) dateFormat else dateWithYear).format(date)
            }
        }.uppercase(Locale.getDefault())
    }

    private companion object {
        const val HEADER = 0
        const val ROW = 1
        const val LOAD_AHEAD = 8
    }
}

/** One recent call. Tap calls back, long press opens the actions. */
class RecentRowView(context: Context) : LinearLayout(context) {
    private val avatar = AvatarView(context)
    private val groupBadge = FrameLayout(context).apply {
        background = context.circle(R.color.surface_raised)
        addView(context.icon("users", R.color.text_secondary, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        visibility = GONE
    }
    private val title = context.label("", TextStyle.BODY_STRONG, maxLines = 1)
    private val direction = IconView(context, "phone_outgoing", R.color.text_tertiary)
    private val detail = context.label("", TextStyle.CALLOUT, R.color.text_secondary, maxLines = 1)
    private val time = context.label("", TextStyle.CAPTION, R.color.text_tertiary)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(Dimens.SCREEN_PADDING), dp(8), dp(Dimens.SCREEN_PADDING), dp(8))
        background = context.ripple(null)
        isClickable = true; isFocusable = true; isLongClickable = true
        val holder = FrameLayout(context)
        holder.addView(avatar, FrameLayout.LayoutParams(dp(48), dp(48)))
        holder.addView(groupBadge, FrameLayout.LayoutParams(dp(48), dp(48)))
        addView(holder, LayoutParams(dp(48), dp(48)))
        val text = LinearLayout(context).apply { orientation = VERTICAL }
        text.addView(title)
        val sub = context.row()
        sub.addView(direction, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
        sub.addView(detail, LayoutParams(0, WRAP, 1f))
        text.addView(sub, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        addView(text, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        addView(time, LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
    }

    fun bind(row: RecentRow, callbacks: RecentsView.Callbacks, clock: String) {
        val negative = CallOutcomes.isNegative(row.kind)
        val labels = row.peers.map(callbacks::nameOf)
        val name = labels.joinToString(", ") { it.text }
        title.text = if (row.count > 1) "$name (${row.count})" else name
        title.setTextColor(context.color(if (negative) R.color.negative else R.color.text_primary))
        if (row.group) {
            avatar.visibility = GONE; groupBadge.visibility = VISIBLE
        } else {
            groupBadge.visibility = GONE; avatar.visibility = VISIBLE
            avatar.bind(row.peers.firstOrNull().orEmpty(), labels.firstOrNull()?.text?.takeIf { labels.first().named })
        }
        val icon = when {
            row.kind == OutcomeKind.MISSED -> "phone_missed"
            row.incoming -> "phone_incoming"
            else -> "phone_outgoing"
        }
        direction.setIcon(icon)
        direction.setTint(if (negative) R.color.negative else R.color.text_tertiary)
        detail.text = detailText(row)
        time.text = clock
        contentDescription = listOf(title.text, detail.text, clock).joinToString(", ")
        setOnClickListener { callbacks.open(row) }
        setOnLongClickListener { callbacks.more(row); true }
    }

    private fun detailText(row: RecentRow): String {
        val kind = context.getString(when (row.kind) {
            OutcomeKind.COMPLETED -> if (row.incoming) R.string.cp_outcome_incoming else R.string.cp_outcome_outgoing
            OutcomeKind.MISSED -> R.string.cp_outcome_missed
            OutcomeKind.DECLINED -> R.string.cp_outcome_declined
            OutcomeKind.CANCELLED -> R.string.cp_outcome_cancelled
            OutcomeKind.NO_ANSWER, OutcomeKind.FAILED -> R.string.cp_outcome_failed
        })
        return if (row.kind == OutcomeKind.COMPLETED && row.durationSeconds > 0) kind + " · " + CallFormat.duration(row.durationSeconds) else kind
    }
}
