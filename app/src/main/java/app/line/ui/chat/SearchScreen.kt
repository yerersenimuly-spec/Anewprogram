package app.line.ui.chat

import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.line.R
import app.line.crypto.ChatMessage
import app.line.ui.AvatarView
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.MATCH
import app.line.ui.Screen
import app.line.ui.TextStyle
import app.line.ui.WRAP
import app.line.ui.color
import app.line.ui.divider
import app.line.ui.dp
import app.line.ui.emptyState
import app.line.ui.iconButton
import app.line.ui.label
import app.line.ui.ripple
import app.line.ui.style
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchRow(val id: String, val peer: String, val sequence: Long, val title: String, val name: String?, val snippet: CharSequence, val stamp: String)

/** Search over message text on this device: everywhere ([peer] null) or inside one conversation. */
class SearchScreen(private val peer: String?) : Screen() {
    private val format by lazy { ChatFormat(context) }
    private lateinit var field: EditText
    private lateinit var clear: View
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = SearchAdapter(peer == null) { row -> host.openConversation(row.peer, row.sequence) }
    private var query = ""
    private var results: List<ChatMessage> = emptyList()
    private var more = false
    private var job: Job? = null
    private var moreJob: Job? = null

    override fun createView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(context.color(R.color.bg))
        field = EditText(context).apply {
            style(TextStyle.BODY)
            background = null
            hint = context.getString(if (peer == null) R.string.search_hint else R.string.cv_search_chat_hint)
            setHintTextColor(context.color(R.color.text_tertiary))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MAX_QUERY))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = hint
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { onQuery(s?.toString().orEmpty()) }
            })
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { host.hideKeyboard(); true } else false }
        }
        clear = context.iconButton("close", context.getString(R.string.cv_clear), tintRes = R.color.text_secondary, sizeDp = 40, iconDp = 20) { field.setText("") }.apply { visibility = View.INVISIBLE }
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), 0, context.dp(8), 0)
            addView(context.iconButton("back", context.getString(R.string.back)) { host.pop() })
            addView(field, LinearLayout.LayoutParams(0, context.dp(Dimens.BAR_HEIGHT), 1f).apply { marginStart = context.dp(4) })
            addView(clear)
        }, LinearLayout.LayoutParams(MATCH, context.dp(Dimens.BAR_HEIGHT)))
        root.addView(context.divider())

        val body = FrameLayout(context)
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SearchScreen.adapter
            itemAnimator = null
            setHasFixedSize(true)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && more && (view.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() >= this@SearchScreen.adapter.itemCount - 5) loadMore()
                }
                override fun onScrollStateChanged(view: RecyclerView, state: Int) { if (state == RecyclerView.SCROLL_STATE_DRAGGING) host.hideKeyboard() }
            })
        }
        empty = context.emptyState("search", context.getString(R.string.search_empty), null).apply { visibility = View.GONE }
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onShown() {
        field.requestFocus()
        field.postDelayed({ context.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }, 220)
    }

    override fun onServiceReady() { if (query.isNotBlank()) search() }

    private fun onQuery(value: String) {
        query = value
        clear.visibility = if (value.isEmpty()) View.INVISIBLE else View.VISIBLE
        search()
    }

    private fun search() {
        job?.cancel(); moreJob?.cancel()
        val service = host.service
        val text = query.trim()
        if (text.isEmpty()) { results = emptyList(); more = false; adapter.submitList(emptyList()); empty.visibility = View.GONE; return }
        if (service == null) return
        job = host.uiScope.launch {
            try {
                delay(250)
                val page = service.searchMessages(text, peer, null)
                results = page
                more = Paging.hasMore(page.size)
                show(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.toast(context.chatErrorText(e))
            }
        }
    }

    private fun loadMore() {
        val service = host.service ?: return
        if (moreJob?.isActive == true || results.isEmpty()) return
        val text = query.trim()
        moreJob = host.uiScope.launch {
            try {
                val page = service.searchMessages(text, peer, results.last().sequence)
                more = Paging.hasMore(page.size)
                val known = results.mapTo(HashSet()) { it.id }
                results = results + page.filter { it.id !in known }
                show(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun show(text: String) {
        val service = host.service
        val titles = results.associate { it.peer to (service?.displayName(it.peer) ?: it.peer.spacedNumber()) }
        val names = results.associate { it.peer to service.knownName(it.peer) }
        val accent = context.color(R.color.accent)
        val rows = withContext(Dispatchers.Default) {
            results.map { message ->
                val snippet = Snippet.around(message.text, text)
                val spannable = SpannableString(snippet.text)
                if (snippet.matchStart >= 0) {
                    spannable.setSpan(ForegroundColorSpan(accent), snippet.matchStart, snippet.matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), snippet.matchStart, snippet.matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                SearchRow(message.id, message.peer, message.sequence, titles[message.peer].orEmpty(), names[message.peer], spannable, format.listStamp(message.createdAt, System.currentTimeMillis()))
            }
        }
        adapter.submitList(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun destroy() { job?.cancel(); moreJob?.cancel() }

    private companion object { const val MAX_QUERY = 200 }
}

private class SearchAdapter(private val showPerson: Boolean, private val onOpen: (SearchRow) -> Unit) :
    ListAdapter<SearchRow, SearchAdapter.Holder>(Diff) {

    class Holder(view: LinearLayout, val avatar: AvatarView, val title: android.widget.TextView, val stamp: android.widget.TextView, val snippet: android.widget.TextView) : RecyclerView.ViewHolder(view)

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = StableIds.of(getItem(position).id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val avatar = AvatarView(context)
        val title = context.label("", TextStyle.BODY_STRONG, maxLines = 1)
        val stamp = context.label("", TextStyle.CAPTION, R.color.text_tertiary, maxLines = 1)
        val snippet = context.label("", TextStyle.CALLOUT, R.color.text_secondary, maxLines = 2)
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(stamp, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(8) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(snippet, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(2) })
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(10), context.dp(Dimens.SCREEN_PADDING), context.dp(10))
            background = context.ripple(null)
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        }
        if (showPerson) row.addView(avatar, LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply { marginEnd = context.dp(14) })
        row.addView(column, LinearLayout.LayoutParams(0, WRAP, 1f))
        val holder = Holder(row, avatar, title, stamp, snippet)
        row.setOnClickListener { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onOpen(getItem(it)) } }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.avatar.bind(row.peer, row.name)
        holder.title.text = if (showPerson) row.title else row.stamp
        holder.stamp.text = row.stamp
        holder.stamp.visibility = if (showPerson) View.VISIBLE else View.GONE
        holder.snippet.text = row.snippet
    }

    private object Diff : DiffUtil.ItemCallback<SearchRow>() {
        override fun areItemsTheSame(a: SearchRow, b: SearchRow) = a.id == b.id
        override fun areContentsTheSame(a: SearchRow, b: SearchRow) = a == b
    }
}
