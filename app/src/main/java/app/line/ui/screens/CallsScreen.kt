package app.line.ui.screens

import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.ActivityEvent
import app.line.CallState
import app.line.R
import app.line.core.NumberInput
import app.line.ui.*
import app.line.ui.calls.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Calls tab: a keypad for new calls and the history of recent ones. */
class CallsScreen : Screen(), RecentsView.Callbacks {
    override val tab = "calls"

    private var buffer = DialBuffer()
    private var pendingMessage: Int? = null
    private var events = ArrayList<ActivityEvent>()
    private var endReached = false
    private var loading = false
    private var loadedOnce = false
    private var loadJob: Job? = null
    private var loadedEventVersion = -1L
    private var seenProfileVersion = 0L

    private lateinit var segments: SegmentedControl
    private lateinit var keypadPage: View
    private lateinit var recents: RecentsView
    private lateinit var ownRow: View
    private lateinit var ownNumber: TextView
    private lateinit var display: TextView
    private lateinit var displayHint: TextView
    private lateinit var status: TextView
    private lateinit var keypad: KeypadView
    private lateinit var callButton: View

    private val state: CallState get() = host.state
    private val maxPeers: Int get() = DialAvailability.maxPeers(state)

    override fun createView(): View {
        val root = context.column()
        root.addView(LargeTitleBar(context, getString(R.string.nav_calls)), LinearLayout.LayoutParams(MATCH, WRAP))

        segments = SegmentedControl(context, listOf(getString(R.string.cp_tab_keypad), getString(R.string.cp_tab_recents))) { show(it, animate = true) }
        root.addView(segments, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = context.dp(Dimens.SCREEN_PADDING); marginEnd = context.dp(Dimens.SCREEN_PADDING); bottomMargin = context.dp(8)
        })

        val pages = FrameLayout(context)
        keypadPage = buildKeypadPage()
        recents = RecentsView(context, this).apply { visibility = View.GONE }
        pages.addView(keypadPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(recents, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        renderAll()
        return root
    }

    private fun getString(res: Int) = context.getString(res)

    private fun buildKeypadPage(): View {
        val inner = context.column { setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(4), context.dp(Dimens.SCREEN_PADDING), context.dp(16)) }

        ownNumber = context.label("", TextStyle.CALLOUT_STRONG)
        ownRow = context.row {
            gravity = Gravity.CENTER
            minimumHeight = context.dp(48)
            background = context.ripple(null, Dimens.RADIUS_M)
            isClickable = true; isFocusable = true
            setOnClickListener { state.number.takeIf { it.isNotEmpty() }?.let { host.copyToClipboard("Line", it) } }
            addView(context.label(getString(R.string.cp_own_number), TextStyle.CALLOUT, R.color.text_tertiary), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = context.dp(8) })
            addView(ownNumber)
            addView(context.icon("copy", R.color.text_tertiary, 16), LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply { marginStart = context.dp(8) })
        }
        ownRow.contentDescription = getString(R.string.copy)
        inner.addView(ownRow, LinearLayout.LayoutParams(MATCH, WRAP))

        display = context.label("", TextStyle.DISPLAY).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            setAutoSizeTextTypeUniformWithConfiguration(20, 38, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
            fontFeatureSettings = "tnum"
            isLongClickable = true
            setOnLongClickListener { pasteNumbers(); true }
        }
        displayHint = context.label(getString(R.string.cp_dial_hint), TextStyle.BODY, R.color.text_tertiary).apply { gravity = Gravity.CENTER }
        val displayArea = FrameLayout(context).apply { minimumHeight = context.dp(88) }
        displayArea.addView(displayHint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        displayArea.addView(display, FrameLayout.LayoutParams(MATCH, MATCH))
        inner.addView(displayArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        status = context.label("", TextStyle.CALLOUT, R.color.text_secondary).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(40)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        inner.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        keypad = KeypadView(
            context, getString(R.string.cp_add_participant), getString(R.string.cp_backspace),
            onDigit = { digit -> edit { it.digit(digit) } },
            onAdd = {
                if (buffer.current.length == DialBuffer.NUMBER_LENGTH && !buffer.canAdd(maxPeers)) setTransient(R.string.cp_err_too_many)
                else edit { it.add(maxPeers) }
            },
            onBackspace = { edit { it.backspace() } },
            onClear = { edit { it.clear() } },
        )
        inner.addView(keypad, LinearLayout.LayoutParams(MATCH, context.dp(4 * 64 + 3 * 8)))

        callButton = context.row {
            gravity = Gravity.CENTER
            background = context.ripple(context.roundRect(R.color.positive, 28), 28)
            isClickable = true; isFocusable = true
            addView(context.icon("phone", R.color.on_accent, 22), LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(10) })
            addView(context.label(getString(R.string.cp_call), TextStyle.BODY_STRONG, R.color.on_accent))
            setOnClickListener { place() }
            Motion.press(this)
        }
        inner.addView(callButton, LinearLayout.LayoutParams(MATCH, context.dp(56)).apply { topMargin = context.dp(16) })

        return ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    // ---- keypad ---------------------------------------------------------------------------------------

    private fun edit(change: (DialBuffer) -> DialBuffer) {
        buffer = change(buffer)
        pendingMessage = null
        renderDial()
    }

    private fun setTransient(res: Int) {
        pendingMessage = res
        UiSounds.play(context, UiCue.ERROR)
        renderDial()
    }

    private fun pasteNumbers() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val pasted = DialBuffer.fromText(text, maxPeers)
        if (pasted.isEmpty) return
        edit { pasted }
    }

    private fun place() {
        val block = DialAvailability.of(state)
        if (block != DialBlock.NONE) return
        when (val result = buffer.validate(state.number, maxPeers)) {
            is NumberInput.Result.Valid -> host.startCall(result.numbers)
            is NumberInput.Result.Invalid -> if (result.problem != NumberInput.Problem.EMPTY) setTransient(problemText(result.problem))
        }
    }

    private fun problemText(problem: NumberInput.Problem): Int = when (problem) {
        NumberInput.Problem.OWN -> R.string.err_own_number
        NumberInput.Problem.DUPLICATE -> R.string.cp_err_duplicate
        NumberInput.Problem.TOO_MANY -> R.string.cp_err_too_many
        else -> R.string.err_number_format
    }

    private fun renderAll() {
        renderDial()
        renderOwn()
    }

    private fun renderOwn() {
        val number = state.number
        ownRow.visibility = if (number.isEmpty()) View.GONE else View.VISIBLE
        ownNumber.text = CallFormat.number(number)
    }

    private fun renderDial() {
        val text = buffer.display()
        display.text = text
        displayHint.visibility = if (buffer.isEmpty) View.VISIBLE else View.GONE
        keypad.setBackspaceEnabled(!buffer.isEmpty)
        keypad.setAddEnabled(buffer.canAdd(maxPeers) || buffer.current.length == DialBuffer.NUMBER_LENGTH)

        val block = DialAvailability.of(state)
        val live = buffer.liveProblem(state.number)
        val message: Pair<Int, Int>? = when {
            live != null -> problemText(live) to R.color.negative
            pendingMessage != null -> pendingMessage!! to R.color.negative
            block != DialBlock.NONE -> blockText(block) to R.color.text_secondary
            else -> null
        }
        if (message == null) {
            status.text = ""
        } else {
            status.text = if (message.first == R.string.cp_err_too_many) context.getString(message.first, state.maxParticipants) else context.getString(message.first)
            status.setTextColor(context.color(message.second))
        }
        val enabled = block == DialBlock.NONE && !buffer.isEmpty && live == null
        callButton.isEnabled = enabled
        callButton.alpha = if (enabled) 1f else 0.4f
        callButton.contentDescription = getString(R.string.cp_call)
    }

    private fun blockText(block: DialBlock): Int = when (block) {
        DialBlock.NOT_CONFIGURED -> R.string.cp_dial_not_configured
        DialBlock.IN_CALL -> R.string.cp_dial_in_call
        DialBlock.CALLS_DISABLED -> R.string.notice_calls_disabled
        DialBlock.MEDIA_UNAVAILABLE -> R.string.cp_dial_media_unavailable
        DialBlock.NONE -> 0
    }

    // ---- pages ----------------------------------------------------------------------------------------

    private fun show(index: Int, animate: Boolean) {
        val toRecents = index == 1
        val incoming: View = if (toRecents) recents else keypadPage
        val outgoing: View = if (toRecents) keypadPage else recents
        outgoing.visibility = View.GONE
        incoming.visibility = View.VISIBLE
        if (animate) Motion.enter(incoming, 140)
        segments.select(index)
        if (toRecents) refreshRecentsIfStale()
        host.hideKeyboard()
    }

    override fun showKeypad() { segments.select(0, animate = true); show(0, animate = true) }

    // ---- recents --------------------------------------------------------------------------------------

    private fun refreshRecentsIfStale() {
        if (!loadedOnce || loadedEventVersion != state.eventVersion) loadFirst()
    }

    private fun loadFirst() {
        val service = host.service
        if (service == null) { if (!recents.hasItems) recents.showLoading(); return }
        loadJob?.cancel()
        loading = true
        val version = state.eventVersion
        endReached = false
        if (!loadedOnce) recents.showLoading()
        loadJob = host.uiScope.launch {
            try {
                val page = service.activityEvents(callsOnly = true)
                events = ArrayList(page)
                endReached = page.size < PAGE
                loadedOnce = true
                loadedEventVersion = version
                renderRecents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (events.isEmpty()) recents.showError()
            } finally {
                loading = false
            }
        }
    }

    override fun loadMore() {
        val service = host.service ?: return
        if (loading || endReached || events.isEmpty()) return
        loading = true
        val before = events.last().timestamp
        loadJob = host.uiScope.launch {
            try {
                val page = service.activityEvents(callsOnly = true, before = before)
                events.addAll(page)
                endReached = page.size < PAGE
                renderRecents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }

    override fun retry() { loadedOnce = false; loadFirst() }

    private fun renderRecents() {
        val items = RecentCalls.build(events, System.currentTimeMillis(), TimeZone.getDefault())
        if (items.isEmpty()) recents.showEmpty() else recents.showItems(items)
    }

    override fun nameOf(number: String): PeerLabel =
        PeerLabels.resolve(number) { host.service?.displayName(it) ?: CallFormat.number(it) }

    override fun open(row: RecentRow) = host.startCall(row.peers)

    override fun more(row: RecentRow) {
        val names = PeerLabels.joined(row.peers) { nameOf(it).text }
        val actions = ArrayList<SheetAction>()
        actions += SheetAction("phone", getString(R.string.cp_action_call)) { host.startCall(row.peers) }
        if (!row.group) {
            val peer = row.peers.first()
            actions += SheetAction("chat", getString(R.string.cp_action_message)) { host.openConversation(peer) }
            actions += SheetAction("copy", getString(R.string.cp_action_copy_number)) { host.copyToClipboard("Line", peer) }
        }
        host.sheet().title(names).actions(actions).show()
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    override fun onShown() {
        renderAll()
        seenProfileVersion = state.profileVersion
        if (segments.selected == 1) refreshRecentsIfStale()
    }

    override fun onServiceReady() {
        if (isBuilt && segments.selected == 1) refreshRecentsIfStale()
    }

    override fun onState(old: CallState, new: CallState) {
        if (!isBuilt) return
        if (old.phase == app.line.Phase.IDLE && new.phase != app.line.Phase.IDLE) { buffer = DialBuffer(); pendingMessage = null }
        if (old.number != new.number) renderOwn()
        if (old.phase != new.phase || old.configReady != new.configReady || old.callsEnabled != new.callsEnabled ||
            old.online != new.online || old.mediaReady != new.mediaReady || old.maxParticipants != new.maxParticipants ||
            old.number != new.number) renderDial()
        if (segments.selected == 1 && new.eventVersion != loadedEventVersion) refreshRecentsIfStale()
        if (new.profileVersion != seenProfileVersion) {
            seenProfileVersion = new.profileVersion
            recents.refreshNames()
        }
    }

    override fun onBack(): Boolean {
        if (segments.selected == 1) { showKeypad(); return true }
        return false
    }

    override fun destroy() { loadJob?.cancel() }

    private companion object { const val PAGE = 40 }
}
