package app.line.ui.screens

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.CallState
import app.line.R
import app.line.ui.*
import app.line.ui.calls.*

/** The live call: ringing, connecting and connected, for one-to-one and group calls, on the lock screen too. */
class ActiveCallScreen : Screen() {
    override val edgeToEdge = true
    override val darkChrome = true

    private var model: CallUiModel? = null
    private var shownProfileVersion = -1L
    private var answering = false
    private var timerRunning = false

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var minimize: View
    private lateinit var ring: PulseRing
    private lateinit var avatarSlot: FrameLayout
    private lateinit var name: TextView
    private lateinit var sub: TextView
    private lateinit var status: TextView
    private lateinit var note: LinearLayout
    private lateinit var noteText: TextView
    private lateinit var roster: LinearLayout
    private lateinit var controls: FrameLayout
    private lateinit var mute: RoundButton
    private lateinit var speaker: RoundButton
    private lateinit var answer: RoundButton
    private lateinit var activeControls: View
    private lateinit var incomingControls: View
    private var controlsMode: CallMode? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            updateTimer()
            status.postDelayed(this, 1_000L - SystemClock.elapsedRealtime() % 1_000L)
        }
    }

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    override fun createView(): View {
        root = FrameLayout(context).apply { background = CallPalette.backdrop(context) }
        content = context.column { setPadding(context.dp(Dimens.SCREEN_PADDING), context.dp(8), context.dp(Dimens.SCREEN_PADDING), context.dp(24)) }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        content.addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(hero(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        controls = FrameLayout(context)
        activeControls = buildActiveControls()
        incomingControls = buildIncomingControls()
        content.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(16) })

        render(host.state, force = true)
        return root
    }

    // ---- building -------------------------------------------------------------------------------------

    private fun topBar(): View {
        val bar = context.row { minimumHeight = context.dp(56) }
        minimize = context.iconButton("chevron_down", str(R.string.cp_minimize), tintRes = R.color.on_accent) { host.minimizeCall() }
        bar.addView(minimize)
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(context.row {
            background = GradientPill.of(context)
            setPadding(context.dp(12), context.dp(6), context.dp(14), context.dp(6))
            addView(context.icon("lock", R.color.on_accent, 14, 2.4f), LinearLayout.LayoutParams(context.dp(14), context.dp(14)).apply { marginEnd = context.dp(6) })
            addView(context.label(str(R.string.cp_e2ee), TextStyle.CAPTION_STRONG).apply { setTextColor(CallPalette.secondary(context)) })
        })
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(View(context), LinearLayout.LayoutParams(context.dp(44), 1))
        return bar
    }

    private fun hero(): View {
        ring = PulseRing(context).apply { setInner(56f) }
        avatarSlot = FrameLayout(context)
        val stage = FrameLayout(context)
        stage.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        stage.addView(avatarSlot, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        name = context.label("", TextStyle.HEADLINE).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(CallPalette.primary(context))
        }
        sub = context.label("", TextStyle.CALLOUT).apply { gravity = Gravity.CENTER; setTextColor(CallPalette.tertiary(context)); fontFeatureSettings = "tnum" }
        status = context.label("", TextStyle.BODY_STRONG).apply {
            gravity = Gravity.CENTER; setTextColor(CallPalette.secondary(context)); fontFeatureSettings = "tnum"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        noteText = context.label("", TextStyle.CAPTION_STRONG, R.color.warning)
        note = context.row {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            background = GradientPill.of(context)
            addView(View(context).apply { background = context.circle(R.color.warning) }, LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(8) })
            addView(noteText)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        roster = context.column {
            visibility = View.GONE
            background = GradientPill.card(context)
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(4))
        }

        val column = context.column {
            gravity = Gravity.CENTER
            addView(stage, LinearLayout.LayoutParams(context.dp(236), context.dp(236)))
            addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
            addView(sub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(4) })
            addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(12) })
            addView(note, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) })
            addView(roster, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(20) })
        }
        return ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
    }

    private fun buildActiveControls(): View {
        mute = RoundButton(context, "mic", str(R.string.cp_mute), RoundKind.GLASS, 64).apply { setOnClickListener { host.service?.toggleMute() } }
        speaker = RoundButton(context, "speaker", str(R.string.cp_speaker_on), RoundKind.GLASS, 64).apply { setOnClickListener { host.service?.toggleSpeaker() } }
        val end = RoundButton(context, "phone_end", str(R.string.cp_end), RoundKind.END, 72).apply { setOnClickListener { host.hangUp() } }
        return context.row {
            gravity = Gravity.CENTER
            listOf(mute, end, speaker).forEach { button ->
                addView(FrameLayout(context).apply { addView(button, FrameLayout.LayoutParams(button.layoutParams.width, button.layoutParams.height, Gravity.CENTER)) },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }
    }

    private fun buildIncomingControls(): View {
        val decline = RoundButton(context, "phone_end", str(R.string.cp_decline), RoundKind.END, 72).apply { setOnClickListener { host.hangUp() } }
        answer = RoundButton(context, "phone", str(R.string.cp_answer), RoundKind.ANSWER, 72).apply {
            setOnClickListener {
                if (answering) return@setOnClickListener
                answering = true
                setAvailable(false)
                host.acceptCall()
                postDelayed({ answering = false; setAvailable(true) }, 4_000)
            }
        }
        fun captioned(button: RoundButton, caption: Int) = context.column {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(button.layoutParams.width, button.layoutParams.height))
            addView(context.label(str(caption), TextStyle.CALLOUT).apply { setTextColor(CallPalette.secondary(context)) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10) })
        }
        return context.row {
            gravity = Gravity.CENTER
            addView(captioned(decline, R.string.cp_decline), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(captioned(answer, R.string.cp_answer), LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    // ---- rendering ------------------------------------------------------------------------------------

    private fun labelOf(number: String): PeerLabel =
        PeerLabels.resolve(number) { host.service?.displayName(it) ?: CallFormat.number(it) }

    private fun render(state: CallState, force: Boolean = false) {
        val next = CallScreenModel.from(state) ?: return
        val names = state.profileVersion
        if (!force && next == model && names == shownProfileVersion) return
        val previous = model
        val namesChanged = names != shownProfileVersion
        model = next
        shownProfileVersion = names
        if (force || namesChanged || previous == null || previous.peers != next.peers || previous.caller != next.caller || previous.group != next.group) bindPeople(next)
        bindStatus(next)
        bindControls(next, animate = previous != null)
        ring.setPulsing(next.mode != CallMode.CONNECTED)
        if (next.group && next.mode != CallMode.INCOMING) bindRoster(next) else roster.visibility = View.GONE
        minimize.visibility = if (next.mode == CallMode.INCOMING) View.INVISIBLE else View.VISIBLE
    }

    private fun bindPeople(m: CallUiModel) {
        avatarSlot.removeAllViews()
        if (m.group) {
            avatarSlot.addView(AvatarStack(context, 72, 22).apply { bind(m.peers, ::labelOf) })
            ring.setInner(48f)
        } else {
            val peer = m.peers.firstOrNull() ?: m.caller.orEmpty()
            val label = labelOf(peer)
            avatarSlot.addView(context.avatar(peer, label.text.takeIf { label.named }, 112))
            ring.setInner(56f)
        }
        val lead = m.caller ?: m.peers.firstOrNull().orEmpty()
        val leadLabel = labelOf(lead)
        name.text = if (m.group && m.mode != CallMode.INCOMING) PeerLabels.joined(m.peers) { labelOf(it).text } else leadLabel.text
        sub.text = when {
            m.group -> str(R.string.cp_group_call, m.groupSize)
            leadLabel.named -> CallFormat.number(lead)
            else -> ""
        }
        sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindStatus(m: CallUiModel) {
        when (m.mode) {
            CallMode.CONNECTED -> {
                status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                startTimer()
            }
            else -> {
                stopTimer()
                status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                status.text = str(when (m.mode) {
                    CallMode.INCOMING -> R.string.cp_status_incoming
                    CallMode.OUTGOING -> R.string.cp_status_calling
                    else -> R.string.cp_status_connecting
                })
            }
        }
        if (m.note == LinkNote.NONE) note.visibility = View.GONE else {
            noteText.text = str(if (m.note == LinkNote.NO_NETWORK) R.string.cp_note_no_network else R.string.cp_note_reconnecting)
            note.visibility = View.VISIBLE
        }
    }

    private fun bindControls(m: CallUiModel, animate: Boolean) {
        val incoming = m.mode == CallMode.INCOMING
        val wanted = if (incoming) CallMode.INCOMING else CallMode.CONNECTED
        val swap = {
            controls.removeAllViews()
            controls.addView(if (incoming) incomingControls else activeControls, FrameLayout.LayoutParams(MATCH, WRAP))
            controlsMode = wanted
        }
        if (controlsMode != wanted) { if (animate && controlsMode != null) Motion.change(controls, swap) else swap() }
        if (!incoming) {
            val connected = m.mode == CallMode.CONNECTED
            mute.setAvailable(connected); speaker.setAvailable(connected)
            mute.setActive(m.muted); mute.setIcon(if (m.muted) "mic_off" else "mic")
            mute.contentDescription = str(if (m.muted) R.string.cp_unmute else R.string.cp_mute)
            speaker.setActive(m.speaker)
            speaker.contentDescription = str(if (m.speaker) R.string.cp_speaker_off else R.string.cp_speaker_on)
        }
    }

    private fun bindRoster(m: CallUiModel) {
        roster.visibility = View.VISIBLE
        roster.removeAllViews()
        m.peers.forEachIndexed { index, number ->
            val label = labelOf(number)
            val present = m.presence[number] == PeerPresence.PRESENT
            roster.addView(context.row {
                minimumHeight = context.dp(52)
                addView(context.avatar(number, label.text.takeIf { label.named }, 36))
                addView(context.label(label.text, TextStyle.CALLOUT_STRONG, maxLines = 1).apply { setTextColor(CallPalette.primary(context)) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
                addView(View(context).apply { background = context.circle(if (present) R.color.positive else R.color.warning) },
                    LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(8); marginStart = context.dp(12) })
                addView(context.label(str(if (present) R.string.cp_in_call else R.string.cp_connecting_peer), TextStyle.CAPTION).apply { setTextColor(CallPalette.secondary(context)) })
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            if (index < m.peers.lastIndex) roster.addView(View(context).apply { setBackgroundColor(CallPalette.glass(context)) }, LinearLayout.LayoutParams(MATCH, 1))
        }
    }

    private fun startTimer() {
        updateTimer()
        if (!timerRunning) { timerRunning = true; status.postDelayed(tick, 1_000L - SystemClock.elapsedRealtime() % 1_000L) }
    }

    private fun stopTimer() { timerRunning = false; status.removeCallbacks(tick) }

    private fun updateTimer() {
        val at = model?.connectedAt ?: return
        status.text = CallFormat.duration(CallFormat.elapsedSeconds(at, SystemClock.elapsedRealtime()))
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    override fun onInsets(top: Int, bottom: Int) {
        content.setPadding(context.dp(Dimens.SCREEN_PADDING), top + context.dp(8), context.dp(Dimens.SCREEN_PADDING), bottom + context.dp(24))
    }

    override fun onShown() {
        host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isBuilt) render(host.state, force = true)
    }

    override fun onHidden() { host.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onServiceReady() { if (isBuilt) render(host.state, force = true) }

    override fun onState(old: CallState, new: CallState) { if (isBuilt) render(new) }

    override fun onBack(): Boolean {
        if (model?.mode != CallMode.INCOMING) host.minimizeCall()
        return true
    }

    override fun destroy() {
        stopTimer()
        host.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
