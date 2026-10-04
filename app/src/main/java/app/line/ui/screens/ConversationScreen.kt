package app.line.ui.screens

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.line.CallState
import app.line.R
import app.line.core.AttachmentView
import app.line.core.TransferStage
import app.line.crypto.ChatMessage
import app.line.media.attachments.VoiceRecorder
import app.line.media.attachments.VoiceRecorderException
import app.line.ui.AvatarView
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.MATCH
import app.line.ui.Screen
import app.line.ui.SheetAction
import app.line.ui.TextStyle
import app.line.ui.UiCue
import app.line.ui.UiSounds
import app.line.ui.WRAP
import app.line.ui.chat.AttachFlow
import app.line.ui.chat.ChatFormat
import app.line.ui.chat.ChatItem
import app.line.ui.chat.ChatItemsBuilder
import app.line.ui.chat.ChatPreview
import app.line.ui.chat.ComposerView
import app.line.ui.chat.ContactScreen
import app.line.ui.chat.Delivery
import app.line.ui.chat.ImageViewerScreen
import app.line.ui.chat.MediaActions
import app.line.ui.chat.MessageAdapter
import app.line.ui.chat.MessageCallbacks
import app.line.ui.chat.MsgItem
import app.line.ui.chat.Paging
import app.line.ui.chat.Playback
import app.line.ui.chat.SearchScreen
import app.line.ui.chat.StableIds
import app.line.ui.chat.TransferUi
import app.line.ui.chat.VoiceController
import app.line.ui.chat.VoiceInput
import app.line.ui.chat.chatErrorText
import app.line.ui.chat.knownName
import app.line.ui.chat.renameSheet
import app.line.ui.chat.spacedNumber
import app.line.ui.chat.startVerification
import app.line.ui.color
import app.line.ui.column
import app.line.ui.divider
import app.line.ui.dp
import app.line.ui.dpf
import app.line.ui.emptyState
import app.line.ui.iconButton
import app.line.ui.label
import app.line.ui.ripple
import app.line.ui.roundRect
import app.line.ui.row
import app.line.ui.textButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One dialog: header, history (newest at the bottom, older pages load on scroll), composer. */
class ConversationScreen(val peer: String, private val focusSequence: Long? = null) : Screen(), MessageCallbacks, VoiceInput {
    private val format by lazy { ChatFormat(context) }
    private val builder by lazy { ChatItemsBuilder(context, format) }
    private val prefs by lazy { context.getSharedPreferences("line-ui", android.content.Context.MODE_PRIVATE) }

    private lateinit var recycler: RecyclerView
    private lateinit var manager: LinearLayoutManager
    private lateinit var adapter: MessageAdapter
    private lateinit var composer: ComposerView
    private lateinit var avatar: AvatarView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var notice: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeAction: TextView
    private lateinit var skeleton: View
    private lateinit var empty: View
    private lateinit var jump: FrameLayout
    private lateinit var jumpBadge: TextView
    private val attach by lazy { AttachFlow(host, peer) }
    private val voice by lazy { VoiceController(context, ::refreshVoice) { host.toast(context.chatErrorText(it)) } }
    private val recorder by lazy { VoiceRecorder(context) }

    private var messages: List<ChatMessage> = emptyList()
    private val knownMedia = HashSet<String>()
    private var verified: Boolean? = null
    private var shown = false
    private var dirty = false
    private var loaded = false
    private var olderAvailable = true
    private var windowCursor: Long? = null
    private var followUntil = 0L
    private var initialPending = true
    private var newWhileAway = 0
    private var focus: Long? = focusSequence
    private val requestedFetch = HashSet<String>()
    private var loadJob: Job? = null
    private var olderJob: Job? = null
    private var rebuildJob: Job? = null

    override fun createView(): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        root.setBackgroundColor(context.color(R.color.bg))
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, context.dp(Dimens.BAR_HEIGHT)))
        root.addView(context.divider())

        val body = FrameLayout(context).apply { clipChildren = false }
        manager = LinearLayoutManager(context).apply { stackFromEnd = true }
        adapter = MessageAdapter(host.uiScope, format, this)
        recycler = RecyclerView(context).apply {
            layoutManager = manager
            adapter = this@ConversationScreen.adapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(0, context.dp(8), 0, context.dp(8))
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.INVISIBLE
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if (dy < 0 && olderAvailable && manager.findFirstVisibleItemPosition() <= 6) loadOlder()
                    renderJump()
                }
            })
        }
        skeleton = ConversationSkeleton(context)
        empty = context.emptyState("lock", context.getString(R.string.conv_empty_title), context.getString(R.string.conv_empty_body)).apply { visibility = View.GONE }
        jump = buildJump()
        body.addView(recycler, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(skeleton, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(jump, FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.BOTTOM or Gravity.END).apply { marginEnd = context.dp(16); bottomMargin = context.dp(12) })
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        notice = buildNotice()
        root.addView(notice, LinearLayout.LayoutParams(MATCH, WRAP))
        composer = ComposerView(context, this).apply {
            onSend = ::sendText
            onAttach = { if (canSend()) attach.open() }
            onHint = { host.toast(R.string.voice_hold_hint) }
        }
        composer.setText(prefs.getString("draft-$peer", "").orEmpty())
        root.addView(composer, LinearLayout.LayoutParams(MATCH, WRAP))
        renderHeader()
        renderNotice()
        return root
    }

    private fun buildHeader(): View = context.row {
        setPadding(context.dp(4), 0, context.dp(8), 0)
        addView(context.iconButton("back", context.getString(R.string.back)) { host.pop() })
        val open = context.row {
            isClickable = true
            isFocusable = true
            background = context.ripple(null, 12)
            setOnClickListener { host.push(ContactScreen(peer, fromConversation = true)) }
            avatar = AvatarView(context)
            addView(avatar, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            val text = context.column {
                title = context.label("", TextStyle.SUBTITLE, maxLines = 1)
                subtitle = context.label("", TextStyle.CAPTION, R.color.text_secondary, maxLines = 1)
                addView(title)
                addView(subtitle, LinearLayout.LayoutParams(WRAP, WRAP))
            }
            addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        }
        addView(open, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(context.iconButton("phone", context.getString(R.string.conv_call)) { host.startCall(listOf(peer)) })
        addView(context.iconButton("more_vertical", context.getString(R.string.more)) { showMenu() })
    }

    private fun buildJump(): FrameLayout = FrameLayout(context).apply {
        background = context.roundRect(R.color.surface, 24, R.color.outline)
        elevation = context.dpf(4f)
        visibility = View.GONE
        isClickable = true
        contentDescription = context.getString(R.string.conv_scroll_down)
        addView(IconView(context, "chevron_down", R.color.text_primary), FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
        jumpBadge = context.label("", TextStyle.MICRO, R.color.on_accent).apply {
            gravity = Gravity.CENTER
            background = context.roundRect(R.color.accent, 9)
            setPadding(context.dp(4), context.dp(2), context.dp(4), context.dp(2))
            minWidth = context.dp(20)
            visibility = View.GONE
        }
        addView(jumpBadge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = context.dp(-6); marginEnd = context.dp(-2) })
        setOnClickListener { jumpToLatest() }
    }

    private fun buildNotice(): LinearLayout = context.row {
        visibility = View.GONE
        setBackgroundColor(context.color(R.color.accent_soft))
        setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(4), context.dp(8), context.dp(4))
        addView(IconView(context, "shield_check", R.color.accent), LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
        noticeText = context.label("", TextStyle.CALLOUT, maxLines = 2)
        addView(noticeText, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12); topMargin = context.dp(8); bottomMargin = context.dp(8) })
        noticeAction = context.textButton(context.getString(R.string.conv_verify_action)) { verify() }
        addView(noticeAction)
    }

    // ---- header and banners ---------------------------------------------------------------------------

    private fun renderHeader() {
        if (!isBuilt) return
        val service = host.service
        val name = service.knownName(peer)
        avatar.bind(peer, name)
        title.text = service?.displayName(peer) ?: peer.spacedNumber()
        val parts = ArrayList<String>(2)
        if (name != null) parts += peer.spacedNumber()
        if (verified == true) parts += context.getString(R.string.contact_verified)
        subtitle.text = parts.joinToString(" · ")
        subtitle.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderNotice() {
        if (!isBuilt) return
        when {
            !host.state.chatEnabled -> {
                notice.visibility = View.VISIBLE
                noticeText.text = context.getString(R.string.err_chat_disabled)
                noticeAction.visibility = View.GONE
            }
            verified == false -> {
                notice.visibility = View.VISIBLE
                noticeText.text = context.getString(R.string.conv_verify_banner)
                noticeAction.visibility = View.VISIBLE
            }
            else -> notice.visibility = View.GONE
        }
    }

    private fun refreshTrust() {
        val service = host.service ?: return
        host.uiScope.launch {
            try { verified = service.verified(peer) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            renderHeader(); renderNotice()
        }
    }

    private fun verify() { host.startVerification(peer) { refreshTrust() } }

    private fun canSend(): Boolean {
        if (!host.state.chatEnabled) { host.toast(R.string.err_chat_disabled); return false }
        if (verified == false) { verify(); return false }
        return true
    }

    // ---- lifecycle -----------------------------------------------------------------------------------

    override fun onShown() {
        shown = true
        host.service?.let { it.setViewing(peer); it.clearMessageNotifications(peer) }
        renderHeader()
        refreshTrust()
        if (!loaded || dirty) refresh(initial = !loaded)
    }

    override fun onHidden() {
        shown = false
        host.service?.setViewing(null)
        saveDraft()
        stopMedia()
    }

    override fun onServiceReady() {
        if (!shown) return
        host.service?.let { it.setViewing(peer); it.clearMessageNotifications(peer) }
        renderHeader()
        refreshTrust()
        refresh(initial = !loaded)
    }

    override fun onState(old: CallState, new: CallState) {
        if (!isBuilt) return
        if (old.chatEnabled != new.chatEnabled) renderNotice()
        if (old.profileVersion != new.profileVersion) renderHeader()
        if (old.chatVersion != new.chatVersion) { if (shown) refresh(initial = false) else dirty = true }
        if (old.attachmentVersion != new.attachmentVersion && loaded) { if (shown) rebuild() else dirty = true }
    }

    override fun destroy() {
        saveDraft()
        stopMedia()
        loadJob?.cancel(); olderJob?.cancel(); rebuildJob?.cancel()
        if (shown) host.service?.setViewing(null)
        shown = false
    }

    private fun saveDraft() {
        if (!isBuilt) return
        val draft = composer.text()
        prefs.edit().apply { if (draft.isBlank()) remove("draft-$peer") else putString("draft-$peer", draft) }.apply()
    }

    private fun stopMedia() {
        voice.stop()
        recorder.cancel()
        if (isBuilt) composer.recordingEnded()
    }

    // ---- loading history -----------------------------------------------------------------------------

    /** Newest-first paging: collects pages ending before [before] until [want] messages are loaded. */
    private suspend fun fetch(want: Int, before: Long?): Pair<List<ChatMessage>, Boolean> {
        val service = host.service ?: return emptyList<ChatMessage>() to false
        var cursor = before
        val result = ArrayList<ChatMessage>()
        var more = true
        while (result.size < want) {
            val page = service.messages(peer, cursor)
            if (page.isEmpty()) { more = false; break }
            result.addAll(0, page)
            cursor = page.first().sequence
            more = Paging.hasMore(page.size)
            if (!more) break
        }
        return result to more
    }

    private fun refresh(initial: Boolean) {
        val service = host.service ?: return
        if (!isBuilt) return
        if (!shown && !initial) { dirty = true; return }
        dirty = false
        loadJob?.cancel()
        loadJob = host.uiScope.launch {
            if (!initial) delay(30)
            try {
                val previousLast = messages.lastOrNull()
                val (list, more) = if (initial) loadInitial(focus) else fetch(max(Paging.PAGE, min(messages.size, MAX_REFRESH)), windowCursor)
                val merged = if (initial) list else Paging.refreshMessages(messages, list)
                if (initial || merged.size <= list.size) olderAvailable = more
                messages = merged
                if (initial) { initialPending = true }
                if (!initial) notifyArrival(previousLast)
                rebuild()
                service.clearMessageNotifications(peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!loaded) skeleton.visibility = View.GONE
                host.toast(context.chatErrorText(e))
            }
        }
    }

    /** Newest page, or the history back to a message reached from search; falls back to a window ending at it. */
    private suspend fun loadInitial(target: Long?): Pair<List<ChatMessage>, Boolean> {
        val service = host.service ?: return emptyList<ChatMessage>() to false
        if (target == null) return fetch(Paging.PAGE, null)
        var (list, more) = fetch(Paging.PAGE, null)
        var pages = 1
        while (more && list.isNotEmpty() && list.first().sequence > target && pages < MAX_FOCUS_PAGES) {
            val older = service.messages(peer, list.first().sequence)
            if (older.isEmpty()) { more = false; break }
            list = older + list
            more = Paging.hasMore(older.size)
            pages++
        }
        if (list.isNotEmpty() && list.first().sequence > target && more) {
            windowCursor = target + 1
            return fetch(Paging.PAGE, windowCursor)
        }
        return list to more
    }

    private fun notifyArrival(previousLast: ChatMessage?) {
        val last = messages.lastOrNull() ?: return
        if (previousLast == null || last.sequence <= previousLast.sequence) return
        val arrived = messages.count { it.sequence > previousLast.sequence && !it.outgoing }
        if (arrived > 0) {
            UiSounds.play(context, UiCue.RECEIVE)
            if (recycler.canScrollVertically(1)) newWhileAway += arrived
        }
    }

    private fun loadOlder() {
        val service = host.service ?: return
        if (olderJob?.isActive == true || messages.isEmpty()) return
        olderJob = host.uiScope.launch {
            try {
                val page = service.messages(peer, messages.first().sequence)
                olderAvailable = Paging.hasMore(page.size)
                messages = Paging.prependMessages(messages, page)
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Re-resolves attachments and redraws the list from [messages]; the latest request wins. */
    private fun rebuild() {
        val service = host.service ?: return
        rebuildJob?.cancel()
        rebuildJob = host.uiScope.launch {
            try {
                val media = messages.filter { it.kind != "text" }
                val fresh = media.filter { it.id !in knownMedia }
                if (fresh.isNotEmpty()) { service.attachments(fresh); knownMedia.addAll(fresh.map { it.id }) }
                val resolved = HashMap<String, AttachmentView?>(media.size)
                for (message in media) resolved[message.id] = service.attachment(message)
                val snapshot = messages
                val items = withContext(Dispatchers.Default) { builder.build(snapshot, resolved, System.currentTimeMillis()) }
                val initial = initialPending
                initialPending = false
                val follow = initial || !recycler.canScrollVertically(1) || SystemClock.uptimeMillis() < followUntil
                adapter.submitList(items) { afterSubmit(items, initial, follow) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.toast(context.chatErrorText(e))
            }
        }
    }

    private fun afterSubmit(items: List<ChatItem>, initial: Boolean, follow: Boolean) {
        if (initial) {
            loaded = true
            recycler.visibility = View.VISIBLE
            skeleton.visibility = View.GONE
            val target = focus
            val index = if (target == null) -1 else items.indexOfFirst { it is MsgItem && it.message.sequence == target }
            if (index >= 0) {
                manager.scrollToPositionWithOffset(index, recycler.height / 2)
                recycler.postDelayed({ (recycler.findViewHolderForItemId(items[index].id) as? MessageAdapter.MsgHolder)?.bubble?.highlight() }, 250)
            } else if (items.isNotEmpty()) {
                manager.scrollToPositionWithOffset(items.lastIndex, 0)
            }
        } else if (follow && items.isNotEmpty() && windowCursor == null) {
            if (ValueAnimator.areAnimatorsEnabled() && recycler.canScrollVertically(1)) recycler.smoothScrollToPosition(items.lastIndex)
            else manager.scrollToPositionWithOffset(items.lastIndex, 0)
            newWhileAway = 0
        }
        empty.visibility = if (loaded && items.isEmpty()) View.VISIBLE else View.GONE
        renderJump()
    }

    private fun renderJump() {
        if (!isBuilt) return
        val away = windowCursor != null || (recycler.canScrollVertically(1) && manager.findLastVisibleItemPosition() < adapter.itemCount - 3)
        if (!away) newWhileAway = 0
        val target = if (away && loaded) View.VISIBLE else View.GONE
        if (jump.visibility != target) {
            if (target == View.VISIBLE) { jump.visibility = View.VISIBLE; jump.alpha = 0f; jump.animate().alpha(1f).setDuration(140).start() }
            else jump.animate().alpha(0f).setDuration(120).withEndAction { jump.visibility = View.GONE }.start()
        }
        jumpBadge.visibility = if (newWhileAway > 0) View.VISIBLE else View.GONE
        if (newWhileAway > 0) jumpBadge.text = if (newWhileAway > 99) "99+" else newWhileAway.toString()
    }

    private fun jumpToLatest() {
        if (windowCursor != null) {
            windowCursor = null
            focus = null
            loaded = false
            messages = emptyList()
            refresh(initial = true)
            return
        }
        newWhileAway = 0
        if (adapter.itemCount > 0) manager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
        renderJump()
    }

    // ---- sending -------------------------------------------------------------------------------------

    private fun sendText(text: String) {
        val service = host.service
        if (service == null) { host.toast(R.string.err_no_connection); return }
        if (!canSend()) return
        val message = text.trim()
        if (message.isEmpty()) return
        composer.clear()
        followUntil = SystemClock.uptimeMillis() + FOLLOW_MS
        host.uiScope.launch {
            try {
                service.sendChat(peer, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (composer.text().isEmpty()) composer.setText(message)
                if (e.message == "SAS must be verified") { verified = false; renderNotice() }
                host.toast(context.chatErrorText(e))
            }
        }
    }

    // ---- voice recording ----------------------------------------------------------------------------

    override fun begin(): Boolean {
        if (!canSend()) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.withMicrophone { host.toast(R.string.voice_hold_hint) }
            return false
        }
        voice.stop()
        val file = File(context.cacheDir, "voice/rec-${System.currentTimeMillis()}.${VoiceRecorder.FILE_EXTENSION}")
        return try {
            recorder.start(file,
                onAutoStop = { result -> composer.recordingEnded(); result?.let(::deliverVoice) },
                onError = { error -> composer.recordingEnded(); host.toast(context.chatErrorText(error)) })
            true
        } catch (e: VoiceRecorderException) {
            host.toast(context.chatErrorText(e))
            false
        }
    }

    override fun end(send: Boolean, heldMs: Long) {
        if (!send) { recorder.cancel(); return }
        val result = try { recorder.stop() } catch (e: VoiceRecorderException) { host.toast(context.chatErrorText(e)); return }
        if (result == null) { host.toast(if (heldMs < TAP_MS) R.string.voice_hold_hint else R.string.voice_too_short); return }
        deliverVoice(result)
    }

    override fun level(): Int = recorder.amplitude.value
    override fun elapsedMs(): Long = recorder.elapsedMs.value

    private fun deliverVoice(result: VoiceRecorder.Result) {
        UiSounds.play(context, UiCue.SEND)
        followUntil = SystemClock.uptimeMillis() + FOLLOW_MS
        attach.sendVoice(result)
    }

    // ---- message callbacks ---------------------------------------------------------------------------

    override fun onTap(item: MsgItem) {
        val message = item.message
        val attachment = item.attachment
        if (attachment == null) {
            if (Delivery.canRetry(message.outgoing, message.state)) retry(message)
            return
        }
        when (TransferUi.tap(attachment.outgoing, attachment.stage, message.state)) {
            TransferUi.Tap.OPEN -> open(item)
            TransferUi.Tap.FETCH -> host.run { host.service?.fetchAttachment(message.id) }
            TransferUi.Tap.RETRY_ATTACHMENT -> host.run { host.service?.retryAttachment(message.id) }
            TransferUi.Tap.RETRY_MESSAGE -> retry(message)
            TransferUi.Tap.MENU -> showActions(item)
            TransferUi.Tap.EXPIRED -> host.toast(R.string.cv_media_expired)
            TransferUi.Tap.NONE -> Unit
        }
    }

    private fun retry(message: ChatMessage) {
        host.run { host.service?.retryMessage(message.id) }
    }

    private fun open(item: MsgItem) {
        val message = item.message
        val payload = item.attachment?.payload ?: return
        when (item.kind) {
            ChatPreview.Kind.IMAGE -> host.push(ImageViewerScreen(message.id, payload.name, payload.mime))
            ChatPreview.Kind.VOICE -> playVoice(item, null)
            ChatPreview.Kind.FILE -> host.run {
                val file = host.service?.plainFile(message.id) ?: return@run
                MediaActions.open(host, file, payload.name, payload.mime, message.id)
            }
            ChatPreview.Kind.TEXT -> Unit
        }
    }

    private fun playVoice(item: MsgItem, fraction: Float?) {
        val message = item.message
        val attachment = item.attachment ?: return
        if (voice.isActive(message.id)) {
            if (fraction == null) voice.pauseOrResume() else voice.seek(fraction)
            return
        }
        host.run {
            val service = host.service ?: return@run
            val file = service.plainFile(message.id)
            val duration = attachment.payload.durationMs ?: 0L
            voice.start(message.id, file, ((fraction ?: 0f) * duration).toInt())
            if (!attachment.outgoing && !attachment.played) service.markPlayed(message.id)
        }
    }

    override fun onSeek(item: MsgItem, fraction: Float) {
        val attachment = item.attachment ?: return
        if (attachment.stage == TransferStage.READY) playVoice(item, fraction) else onTap(item)
    }

    override fun onSpeed() = voice.cycleSpeed()

    override fun onLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            host.toast(R.string.media_no_app)
        }
    }

    override fun onBound(item: MsgItem) {
        val attachment = item.attachment ?: return
        if (TransferUi.autoFetch(attachment.outgoing, item.kind, attachment.stage) && requestedFetch.add(item.message.id)) {
            host.uiScope.launch {
                try { host.service?.fetchAttachment(item.message.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    override fun playback(id: String): Playback? = voice.playback(id)

    override suspend fun plainFile(id: String): File = host.service?.plainFile(id) ?: error("Offline")

    private fun refreshVoice(id: String) {
        if (!isBuilt) return
        val holder = recycler.findViewHolderForItemId(StableIds.of(id)) as? MessageAdapter.MsgHolder ?: return
        holder.voice?.setPlayback(voice.playback(id))
    }

    // ---- menus ---------------------------------------------------------------------------------------

    private fun showMenu() {
        host.sheet().title(title.text).actions(listOf(
            SheetAction("search", context.getString(R.string.conv_menu_search)) { host.push(SearchScreen(peer)) },
            SheetAction("shield_check", context.getString(R.string.conv_menu_verify)) { verify() },
            SheetAction("edit", context.getString(R.string.conv_menu_rename)) { host.renameSheet(peer) { renderHeader() } },
            SheetAction("trash", context.getString(R.string.conv_menu_clear), destructive = true) { confirmClear() },
        )).show()
    }

    private fun confirmClear() {
        host.sheet().title(context.getString(R.string.conv_clear_title)).message(context.getString(R.string.conv_clear_body))
            .buttons(context.getString(R.string.conv_clear_confirm), destructive = true, secondary = context.getString(R.string.cancel)) {
                host.run { host.service?.clearConversation(peer) }
            }.show()
    }

    override fun onLongPress(item: MsgItem) = showActions(item)

    private fun showActions(item: MsgItem) {
        val message = item.message
        val attachment = item.attachment
        val actions = ArrayList<SheetAction>(6)
        val text = (item.body ?: "").toString()
        if (text.isNotBlank() && item.kind != ChatPreview.Kind.VOICE) {
            actions += SheetAction("copy", context.getString(R.string.msg_copy)) { host.copyToClipboard("Line", text) }
        }
        if (Delivery.canRetry(message.outgoing, message.state)) {
            actions += SheetAction("refresh", context.getString(R.string.msg_retry)) { onTap(item) }
        }
        if (attachment != null && attachment.stage == TransferStage.READY) {
            val payload = attachment.payload
            if (item.kind == ChatPreview.Kind.FILE) actions += SheetAction("file", context.getString(R.string.msg_open)) {
                host.run { MediaActions.open(host, plainFile(message.id), payload.name, payload.mime, message.id) }
            }
            actions += SheetAction("download", context.getString(R.string.msg_save)) {
                host.run { MediaActions.save(host, plainFile(message.id), payload.name, payload.mime, message.id) }
            }
            actions += SheetAction("share", context.getString(R.string.cv_msg_share)) {
                host.run { MediaActions.share(host, plainFile(message.id), payload.name, payload.mime, message.id) }
            }
        }
        actions += SheetAction("trash", context.getString(R.string.msg_delete), destructive = true) { confirmDelete(message) }
        host.sheet().actions(actions).show()
    }

    private fun confirmDelete(message: ChatMessage) {
        host.sheet().title(context.getString(R.string.msg_delete_title)).message(context.getString(R.string.msg_delete_body))
            .buttons(context.getString(R.string.delete), destructive = true, secondary = context.getString(R.string.cancel)) {
                host.run { host.service?.deleteMessage(message.id) }
            }.show()
    }

    private companion object {
        const val MAX_REFRESH = 240
        const val MAX_FOCUS_PAGES = 12
        const val TAP_MS = 350L
        const val FOLLOW_MS = 3_000L
    }
}

/** Placeholder bubbles shown until the first page of history arrives. */
private class ConversationSkeleton(context: android.content.Context) : LinearLayout(context) {
    private val animator = ValueAnimator.ofFloat(0.45f, 1f).apply {
        duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(12), dp(8), dp(12), dp(16))
        listOf(false to 190, true to 140, true to 220, false to 120, false to 230, true to 160).forEach { (outgoing, width) ->
            val bubble = View(context).apply { background = context.roundRect(if (outgoing) R.color.accent_soft else R.color.surface_raised, Dimens.RADIUS_L) }
            addView(bubble, LayoutParams(dp(width), dp(40)).apply {
                gravity = if (outgoing) Gravity.END else Gravity.START
                topMargin = dp(8)
            })
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}
