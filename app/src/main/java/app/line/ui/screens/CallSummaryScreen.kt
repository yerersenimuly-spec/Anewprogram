package app.line.ui.screens

import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.CallState
import app.line.Notice
import app.line.R
import app.line.core.CallSummary
import app.line.ui.*
import app.line.ui.calls.*
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What remains of a call: who, how it ended, how long, and the next step. Dismissed by the user, never on a timer. */
class CallSummaryScreen(private val summary: CallSummary) : Screen() {
    override val edgeToEdge = true
    override val modal = true
    override val darkChrome = true

    private lateinit var model: SummaryModel
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var avatarSlot: FrameLayout
    private lateinit var name: TextView
    private var entered = false
    private val staged = ArrayList<View>()

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    override fun createView(): View {
        val state = host.state
        val notice = if (state.lastCall?.id == summary.id) state.notice else Notice.NONE
        model = CallOutcomes.summary(summary, notice)

        column = context.column {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(Dimens.SCREEN_PADDING + 4), context.dp(12), context.dp(Dimens.SCREEN_PADDING + 4), context.dp(24))
        }
        scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        column.addView(View(context).apply {
            background = context.roundRect(R.color.on_accent, 3).apply { alpha = 60 }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(40), context.dp(5)))
        column.addView(spacer(1f))
        buildHero()
        column.addView(spacer(1f))
        buildActions()

        val root = SwipeDismissLayout(context, scroll, atTop = { scroll.scrollY == 0 }, onDismiss = { close() })
        root.background = CallPalette.backdrop(context)
        return root
    }

    private fun spacer(weight: Float) = View(context).also { it.layoutParams = LinearLayout.LayoutParams(MATCH, 0, weight) }

    private fun toneColor(): Int = when (model.tone) {
        Tone.POSITIVE -> context.color(R.color.positive)
        Tone.NEGATIVE -> context.color(R.color.negative)
        Tone.NEUTRAL -> CallPalette.secondary(context)
    }

    // ---- content --------------------------------------------------------------------------------------

    private fun buildHero() {
        val glowColor = when (model.tone) {
            Tone.POSITIVE -> context.color(R.color.accent)
            Tone.NEGATIVE -> context.color(R.color.negative)
            Tone.NEUTRAL -> CallPalette.primary(context)
        }
        val stage = FrameLayout(context).apply { clipChildren = false }
        stage.addView(View(context).apply {
            background = CallPalette.glow(context, glowColor, if (model.tone == Tone.NEUTRAL) 0.14f else 0.34f, 132f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        avatarSlot = FrameLayout(context)
        stage.addView(avatarSlot, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        column.addView(stage, LinearLayout.LayoutParams(context.dp(264), context.dp(264)))
        staged += stage

        val headline = context.row {
            gravity = Gravity.CENTER
            addView(FrameLayout(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(toneColor().withAlpha(0.2f)) }
                addView(IconView(context, headlineIcon(), R.color.on_accent).apply { setTintColor(toneColor()) },
                    FrameLayout.LayoutParams(context.dp(18), context.dp(18), Gravity.CENTER))
            }, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply { marginEnd = context.dp(10) })
            addView(context.label(str(headlineText()), TextStyle.HEADLINE).apply { setTextColor(CallPalette.primary(context)); gravity = Gravity.CENTER })
        }
        column.addView(headline, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
        staged += headline

        name = context.label("", TextStyle.TITLE).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(CallPalette.secondary(context))
        }
        column.addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
        staged += name

        if (model.showDuration) {
            val duration = context.label(CallFormat.duration(summary.durationSeconds), TextStyle.DISPLAY).apply {
                textSize = 56f
                typeface = Fonts.get(context, 500)
                letterSpacing = -0.02f
                fontFeatureSettings = "tnum"
                gravity = Gravity.CENTER
                setTextColor(CallPalette.primary(context))
                contentDescription = CallFormat.duration(summary.durationSeconds)
            }
            column.addView(duration, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(16) })
            staged += duration
        }

        val meta = context.label(metaText(), TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setTextColor(CallPalette.tertiary(context)) }
        column.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(if (model.showDuration) 8 else 12) })
        staged += meta

        if (model.secure) {
            val secure = context.row {
                gravity = Gravity.CENTER
                addView(context.icon("lock", R.color.on_accent, 14, 2.4f).apply { setTintColor(CallPalette.tertiary(context)) },
                    LinearLayout.LayoutParams(context.dp(14), context.dp(14)).apply { marginEnd = context.dp(6) })
                addView(context.label(str(R.string.cp_e2ee), TextStyle.CAPTION).apply { setTextColor(CallPalette.tertiary(context)) })
            }
            column.addView(secure, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(6) })
            staged += secure
        }

        model.reason?.let { reason ->
            val line = context.label(str(noticeText(reason)), TextStyle.CALLOUT).apply {
                gravity = Gravity.CENTER; maxLines = 2; setTextColor(CallPalette.secondary(context))
            }
            column.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(16) })
            staged += line
        }
        bindPeople()
    }

    private fun bindPeople() {
        avatarSlot.removeAllViews()
        val peers = summary.peers
        val labelOf = { number: String -> PeerLabels.resolve(number) { host.service?.displayName(it) ?: CallFormat.number(it) } }
        if (summary.group) {
            avatarSlot.addView(AvatarStack(context, 76, 24).apply { bind(peers, labelOf) })
            name.text = PeerLabels.joined(peers) { labelOf(it).text }
        } else {
            val peer = peers.firstOrNull().orEmpty()
            val label = labelOf(peer)
            avatarSlot.addView(context.avatar(peer, label.text.takeIf { label.named }, 112))
            name.text = label.text
        }
    }

    private fun buildActions() {
        val actions = context.column { gravity = Gravity.CENTER_HORIZONTAL }
        val primaryLabel = when (model.action) {
            PrimaryAction.CALL_BACK -> R.string.cp_call_back
            PrimaryAction.CALL_AGAIN -> R.string.cp_call_again
            PrimaryAction.TRY_AGAIN -> R.string.cp_try_again
        }
        val buttons = context.row {
            addView(context.primaryButton(str(primaryLabel)) { callAgain() }, LinearLayout.LayoutParams(0, WRAP, 1.3f))
            if (model.canMessage) addView(glassButton(str(R.string.cp_action_message)) { message() },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        }
        actions.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
        actions.addView(context.textButton(str(R.string.close), R.color.on_accent) { close() }.apply { setTextColor(CallPalette.secondary(context)) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) })
        column.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(24) })
        staged += actions
    }

    private fun glassButton(text: String, onClick: () -> Unit): TextView = context.label(text, TextStyle.BODY_STRONG).apply {
        setTextColor(CallPalette.primary(context))
        gravity = Gravity.CENTER
        minHeight = context.dp(52)
        setPadding(context.dp(20), 0, context.dp(20), 0)
        val base = android.graphics.drawable.GradientDrawable().apply { setColor(CallPalette.glass(context)); cornerRadius = context.dpf(16f) }
        val mask = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = context.dpf(16f) }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(CallPalette.glassPressed(context)), base, mask)
        isClickable = true; isFocusable = true
        setOnClickListener { UiSounds.play(context, UiCue.TAP); onClick() }
        Motion.press(this)
    }

    // ---- copy -----------------------------------------------------------------------------------------

    private fun headlineText(): Int = when (model.kind) {
        OutcomeKind.COMPLETED -> R.string.cp_sum_completed
        OutcomeKind.MISSED -> R.string.cp_sum_missed
        OutcomeKind.DECLINED -> R.string.cp_sum_declined
        OutcomeKind.CANCELLED -> R.string.cp_sum_cancelled
        OutcomeKind.NO_ANSWER -> R.string.cp_sum_no_answer
        OutcomeKind.FAILED -> R.string.cp_sum_failed
    }

    private fun headlineIcon(): String = when (model.kind) {
        OutcomeKind.MISSED -> "phone_missed"
        OutcomeKind.FAILED -> "alert"
        OutcomeKind.DECLINED, OutcomeKind.CANCELLED -> "phone_end"
        else -> if (summary.incoming) "phone_incoming" else "phone_outgoing"
    }

    private fun metaText(): String {
        val locale = Locale.getDefault()
        val at = Date(summary.startedAt)
        val clock = DateFormat.getTimeFormat(context).format(at)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = at }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val day = if (sameDay) str(R.string.cp_today) else {
            val skeleton = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "dMMMM" else "dMMMMy"
            java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(at)
        }
        val direction = if (model.kind == OutcomeKind.MISSED) null else str(if (summary.incoming) R.string.cp_outcome_incoming else R.string.cp_outcome_outgoing)
        val group = if (summary.group) str(R.string.cp_group_call, summary.peers.size + 1) else null
        return listOfNotNull("$day, $clock", direction, group).joinToString(" · ")
    }

    private fun noticeText(notice: Notice): Int = when (notice) {
        Notice.CALL_NETWORK_LOST -> R.string.notice_call_network_lost
        Notice.CALL_MEDIA_LOST -> R.string.notice_call_media_lost
        Notice.CALL_MEDIA_FAILED -> R.string.notice_call_media_failed
        Notice.CALL_SECURE_FAILED -> R.string.notice_call_secure_failed
        Notice.CALL_PROTOCOL -> R.string.notice_call_protocol
        Notice.CALL_UNAVAILABLE -> R.string.notice_call_unavailable
        Notice.CALLS_DISABLED -> R.string.notice_calls_disabled
        Notice.MIC_REQUIRED -> R.string.notice_mic_required
        else -> R.string.notice_replaced
    }

    // ---- actions --------------------------------------------------------------------------------------

    private fun close() {
        host.service?.dismissLastCall()
        host.pop()
    }

    private fun callAgain() {
        host.service?.dismissLastCall()
        host.pop()
        host.startCall(summary.peers)
    }

    private fun message() {
        val peer = summary.peers.firstOrNull() ?: return
        host.service?.dismissLastCall()
        host.openConversation(peer)
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    override fun onInsets(top: Int, bottom: Int) {
        column.setPadding(context.dp(Dimens.SCREEN_PADDING + 4), top + context.dp(12), context.dp(Dimens.SCREEN_PADDING + 4), bottom + context.dp(24))
    }

    override fun onShown() {
        if (entered) return
        entered = true
        staged.forEachIndexed { index, view ->
            Motion.enter(view, 220)
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) view.animate().setStartDelay(index * 45L)
        }
    }

    override fun onServiceReady() { if (isBuilt) bindPeople() }

    override fun onState(old: CallState, new: CallState) {
        if (isBuilt && old.profileVersion != new.profileVersion) bindPeople()
    }

    override fun onBack(): Boolean { close(); return true }
}
