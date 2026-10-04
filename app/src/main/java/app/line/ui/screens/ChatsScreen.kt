package app.line.ui.screens

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.line.CallState
import app.line.R
import app.line.crypto.Conversation
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.LargeTitleBar
import app.line.ui.MATCH
import app.line.ui.Screen
import app.line.ui.SheetAction
import app.line.ui.SkeletonList
import app.line.ui.TextStyle
import app.line.ui.WRAP
import app.line.ui.chat.ChatFormat
import app.line.ui.chat.ChatListAdapter
import app.line.ui.chat.ChatPreview
import app.line.ui.chat.ChatRow
import app.line.ui.chat.ContactScreen
import app.line.ui.chat.NewChatScreen
import app.line.ui.chat.Paging
import app.line.ui.chat.SearchScreen
import app.line.ui.chat.knownName
import app.line.ui.chat.spacedNumber
import app.line.ui.chat.startVerification
import app.line.ui.color
import app.line.ui.column
import app.line.ui.dp
import app.line.ui.emptyState
import app.line.ui.label
import app.line.ui.ripple
import app.line.ui.roundRect
import app.line.ui.row
import app.line.ui.textButton
import app.line.ui.tintedIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** Root tab: the list of dialogs, newest first, with the banner for senders waiting to be verified. */
class ChatsScreen : Screen() {
    override val tab = "chats"

    private val format by lazy { ChatFormat(context) }
    private lateinit var list: RecyclerView
    private lateinit var skeleton: SkeletonList
    private lateinit var empty: View
    private lateinit var failed: View
    private lateinit var held: LinearLayout
    private lateinit var heldTitle: android.widget.TextView
    private lateinit var heldBody: android.widget.TextView
    private val adapter = ChatListAdapter(onOpen = { host.openConversation(it.peer) }, onMenu = ::showMenu)

    private var conversations: List<Conversation> = emptyList()
    private var loaded = false
    private var shown = false
    private var hasMore = true
    private var loading = false
    private var job: Job? = null
    private var moreJob: Job? = null

    override fun createView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(context.color(R.color.bg))
        root.addView(LargeTitleBar(context, context.getString(R.string.nav_chats))
            .action("search", context.getString(R.string.chats_search)) { host.push(SearchScreen(null)) }
            .action("compose", context.getString(R.string.chats_new)) { host.push(NewChatScreen()) })
        held = buildHeldBanner()
        root.addView(held, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = context.dp(Dimens.SCREEN_PADDING - 4); marginEnd = context.dp(Dimens.SCREEN_PADDING - 4); bottomMargin = context.dp(8)
        })
        val body = FrameLayout(context)
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ChatsScreen.adapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(0, 0, 0, context.dp(8))
            visibility = View.GONE
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    val manager = view.layoutManager as LinearLayoutManager
                    if (dy > 0 && hasMore && manager.findLastVisibleItemPosition() >= this@ChatsScreen.adapter.itemCount - 5) loadMore()
                }
            })
        }
        skeleton = SkeletonList(context)
        empty = context.emptyState("chat", context.getString(R.string.chats_empty_title), context.getString(R.string.chats_empty_body),
            context.getString(R.string.chats_new)) { host.push(NewChatScreen()) }.apply { visibility = View.GONE }
        failed = context.emptyState("wifi_off", context.getString(R.string.cv_load_failed), null, context.getString(R.string.cv_retry)) { reload(force = true) }
            .apply { visibility = View.GONE }
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(skeleton, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        body.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(failed, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        renderHeld(host.state)
        return root
    }

    private fun buildHeldBanner(): LinearLayout = context.row {
        visibility = View.GONE
        background = context.ripple(context.roundRect(R.color.accent_soft, Dimens.RADIUS_L), Dimens.RADIUS_L)
        isClickable = true
        isFocusable = true
        setPadding(context.dp(16), context.dp(12), context.dp(8), context.dp(12))
        addView(context.tintedIcon("shield_check"), LinearLayout.LayoutParams(context.dp(36), context.dp(36)))
        val text = context.column {
            heldTitle = context.label("", TextStyle.BODY_STRONG, maxLines = 1)
            heldBody = context.label("", TextStyle.CAPTION, R.color.text_secondary, maxLines = 1)
            addView(heldTitle)
            addView(heldBody, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(2) })
        }
        addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        addView(context.textButton(context.getString(R.string.held_action)) { openHeld() })
        setOnClickListener { openHeld() }
    }

    private fun renderHeld(state: CallState) {
        val count = state.heldSenders
        held.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count == 0) return
        heldTitle.text = context.getString(if (count == 1) R.string.held_title else R.string.cv_held_title_many)
        val only = host.service?.heldSenders()?.singleOrNull()
        heldBody.text = if (count == 1 && only != null) context.getString(R.string.held_body, host.service.knownName(only) ?: only.spacedNumber())
            else context.getString(R.string.cv_held_body_many, count)
    }

    private fun openHeld() {
        val peers = host.service?.heldSenders()?.toList().orEmpty()
        when (peers.size) {
            0 -> Unit
            1 -> host.startVerification(peers[0])
            else -> host.sheet().title(context.getString(R.string.held_title)).actions(peers.map { peer ->
                SheetAction("shield_check", host.service.knownName(peer) ?: peer.spacedNumber()) { host.startVerification(peer) }
            }).show()
        }
    }

    // ---- loading ---------------------------------------------------------------------------------------

    override fun onServiceReady() { reload() }

    override fun onShown() { shown = true; reload() }

    override fun onHidden() { shown = false }

    override fun onState(old: CallState, new: CallState) {
        if (!isBuilt || !shown) return
        if (old.heldSenders != new.heldSenders) renderHeld(new)
        if (old.chatVersion != new.chatVersion || old.profileVersion != new.profileVersion || old.pending != new.pending) reload()
    }

    private fun reload(force: Boolean = false) {
        val service = host.service ?: return
        if (!isBuilt) return
        job?.cancel()
        job = host.uiScope.launch {
            delay(if (force) 0 else 40)
            val target = max(Paging.PAGE, conversations.size)
            try {
                val fresh = ArrayList<Conversation>()
                var page = service.conversations(null)
                fresh += page
                while (fresh.size < target && Paging.hasMore(page.size)) {
                    page = service.conversations(fresh.last().last.sequence)
                    fresh += page
                }
                hasMore = Paging.hasMore(page.size)
                conversations = fresh
                loaded = true
                submit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!loaded) { skeleton.visibility = View.GONE; failed.visibility = View.VISIBLE }
            }
        }
        renderHeld(host.state)
    }

    private fun loadMore() {
        val service = host.service ?: return
        if (loading || moreJob?.isActive == true || conversations.isEmpty()) return
        loading = true
        moreJob = host.uiScope.launch {
            try {
                val page = service.conversations(conversations.last().last.sequence)
                hasMore = Paging.hasMore(page.size)
                conversations = Paging.appendConversations(conversations, page)
                submit()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally { loading = false }
        }
    }

    private fun submit() {
        val service = host.service
        val now = System.currentTimeMillis()
        val rows = conversations.map { toRow(it, now) }
        adapter.submitList(rows)
        skeleton.visibility = View.GONE
        failed.visibility = View.GONE
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toRow(conversation: Conversation, now: Long): ChatRow {
        val service = host.service
        val last = conversation.last
        val peer = conversation.peer
        val title = service?.displayName(peer) ?: peer.spacedNumber()
        val preview = ChatPreview.of(last.kind, last.text)
        val body = when {
            preview.kind == ChatPreview.Kind.TEXT -> preview.text
            preview.text.isEmpty() -> format.kindLabel(preview.kind)
            else -> format.kindLabel(preview.kind) + ", " + preview.text
        }
        val line = if (last.outgoing) context.getString(R.string.chats_you_prefix, body) else body
        val stamp = format.listStamp(last.createdAt, now)
        val status = if (last.outgoing) last.state else null
        val description = buildString {
            append(title).append(", ").append(line).append(", ").append(stamp)
            if (conversation.unread > 0) append(", ").append(context.getString(R.string.cv_unread, conversation.unread))
        }
        return ChatRow(peer, title, service.knownName(peer), line, preview.kind, stamp, conversation.unread, status, description)
    }

    // ---- actions ---------------------------------------------------------------------------------------

    private fun showMenu(row: ChatRow) {
        host.sheet().title(row.title).actions(listOf(
            SheetAction("person", context.getString(R.string.contact_title)) { host.push(ContactScreen(row.peer)) },
            SheetAction("trash", context.getString(R.string.chat_delete_history), destructive = true) { confirmClear(row) },
        )).show()
    }

    private fun confirmClear(row: ChatRow) {
        host.sheet().title(context.getString(R.string.conv_clear_title)).message(context.getString(R.string.conv_clear_body))
            .buttons(context.getString(R.string.conv_clear_confirm), destructive = true, secondary = context.getString(R.string.cancel)) {
                host.run { host.service?.clearConversation(row.peer); reload(force = true) }
            }.show()
    }
}
