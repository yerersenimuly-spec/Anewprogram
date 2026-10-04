package app.line.ui.chat

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.R
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.Motion
import app.line.ui.TextStyle
import app.line.ui.UiCue
import app.line.ui.UiSounds
import app.line.ui.accentGradient
import app.line.ui.circle
import app.line.ui.color
import app.line.ui.dp
import app.line.ui.dpf
import app.line.ui.iconButton
import app.line.ui.label
import app.line.ui.ripple
import app.line.ui.roundRect
import app.line.ui.style
import kotlin.math.max

/** What the composer needs from the screen to record a voice message. */
interface VoiceInput {
    /** Starts the recorder; false when it did not start (permission being asked, microphone busy). */
    fun begin(): Boolean

    /** Ends the recording; [send] false discards it. [heldMs] is how long the button was down. */
    fun end(send: Boolean, heldMs: Long)

    fun level(): Int
    fun elapsedMs(): Long
}

/** Scrolling level meter: newest sample on the right (start side in RTL). */
class LevelMeterView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.accent) }
    private val rect = RectF()
    private val barWidth = dpf(3f)
    private val gap = dpf(2f)
    private var levels = FloatArray(0)
    private var count = 0

    fun push(amplitude: Int) {
        if (levels.isEmpty()) return
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = kotlin.math.sqrt(amplitude.coerceIn(0, 32767) / 32767f)
        count = minOf(count + 1, levels.size)
        invalidate()
    }

    fun clear() { levels.fill(0f); count = 0; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val slots = max(1, ((w + gap) / (barWidth + gap)).toInt())
        if (levels.size != slots) levels = FloatArray(slots)
    }

    override fun onDraw(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (index in levels.indices) {
            val barHeight = max(dpf(3f), levels[index] * height)
            val left = index * (barWidth + gap)
            val x = if (rtl) width - left - barWidth else left
            rect.set(x, (height - barHeight) / 2f, x + barWidth, (height + barHeight) / 2f)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}

/**
 * Message composer: attach, auto-growing field, and one round button that is Send when there is text and the
 * microphone otherwise (hold to record, slide to the side to cancel, slide up to lock).
 */
class ComposerView(context: Context, private val voice: VoiceInput) : FrameLayout(context) {
    var onSend: ((String) -> Unit)? = null
    var onAttach: (() -> Unit)? = null
    var onHint: (() -> Unit)? = null

    val field = EditText(context)
    private val attach: View = context.iconButton("attach", context.getString(R.string.conv_attach), tintRes = R.color.text_secondary) { onAttach?.invoke() }
    private val action = FrameLayout(context)
    private val micIcon = IconView(context, "mic", R.color.on_accent)
    private val sendIcon = IconView(context, "send", R.color.on_accent)
    private val inputRow = LinearLayout(context)

    private val bar = LinearLayout(context)
    private val dot = View(context)
    private val timer = context.label("0:00", TextStyle.SUBTITLE)
    private val meter = LevelMeterView(context)
    private val hint = context.label(context.getString(R.string.voice_slide_cancel), TextStyle.CALLOUT, R.color.text_secondary, maxLines = 1)
    private val trash: View
    private val lockPill = FrameLayout(context)
    private val lockIcon = IconView(context, "lock", R.color.text_secondary)

    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private var gesture: RecordGesture? = null
    private var pressedAt = 0L
    private var recording = false
    private var shownSeconds = -1L
    private var lastMeterAt = 0L
    private var pulse: ValueAnimator? = null
    private var sendMode = false

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = voice.elapsedMs()
            val seconds = elapsed / 1000
            if (seconds != shownSeconds) { shownSeconds = seconds; timer.text = VoiceMath.duration(elapsed) }
            val now = SystemClock.uptimeMillis()
            if (now - lastMeterAt >= 60) { lastMeterAt = now; meter.push(voice.level()) }
            postOnAnimation(this)
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(context.color(R.color.surface))

        field.style(TextStyle.BODY)
        field.hint = context.getString(R.string.conv_message_hint)
        field.setHintTextColor(context.color(R.color.text_tertiary))
        field.background = context.roundRect(R.color.surface_raised, 22)
        field.setPadding(dp(16), dp(11), dp(16), dp(11))
        field.minHeight = dp(44)
        field.maxLines = 5
        field.gravity = Gravity.CENTER_VERTICAL
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        field.filters = arrayOf<InputFilter>(Utf8LengthFilter())
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { renderActionMode(animate = true) }
        })

        action.background = context.accentGradient(22).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        action.addView(micIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        action.addView(sendIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        action.isClickable = true
        action.isFocusable = true
        action.setOnTouchListener { _, event -> onActionTouch(event) }
        action.setOnClickListener { if (sendMode || locked) submit() else onHint?.invoke() }

        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.BOTTOM
        inputRow.setPadding(dp(4), dp(8), dp(8), dp(8))
        inputRow.addView(attach, LinearLayout.LayoutParams(dp(44), dp(44)))
        inputRow.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        inputRow.addView(action, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        trash = context.iconButton("trash", context.getString(R.string.voice_cancel), tintRes = R.color.negative) { finish(send = false) }.apply { visibility = View.GONE }
        dot.background = context.circle(R.color.negative)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(context.color(R.color.surface))
        bar.setPadding(dp(12), dp(8), dp(4), dp(8))
        bar.visibility = View.GONE
        bar.addView(trash, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(4); marginEnd = dp(10) })
        bar.addView(timer, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(meter, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginEnd = dp(8) })
        bar.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.contentDescription = context.getString(R.string.voice_recording)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.BOTTOM).apply { marginEnd = dp(60) })

        lockPill.background = context.roundRect(R.color.surface_raised, 22)
        lockPill.visibility = View.GONE
        lockPill.elevation = dpf(2f)
        lockPill.addView(lockIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        lockPill.contentDescription = context.getString(R.string.cv_voice_lock)
        addView(lockPill, LayoutParams(dp(44), dp(60), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(8); bottomMargin = dp(64) })

        Motion.press(action)
        renderActionMode(animate = false)
    }

    private val locked get() = gesture?.state == RecordGesture.State.LOCKED

    fun text(): String = field.text.toString()

    fun setText(value: String) { field.setText(value); field.setSelection(field.text.length) }

    fun clear() { field.setText("") }

    private fun submit() {
        if (recording && locked) { finish(send = true); return }
        val value = field.text.toString()
        if (value.isBlank()) return
        UiSounds.play(context, UiCue.SEND)
        onSend?.invoke(value)
    }

    private fun renderActionMode(animate: Boolean) {
        val send = field.text.isNotBlank()
        if (send == sendMode && animate) return
        sendMode = send
        action.contentDescription = context.getString(if (send) R.string.conv_send else R.string.conv_voice)
        fun swap(shown: View, hidden: View) {
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
                shown.alpha = 1f; shown.scaleX = 1f; shown.scaleY = 1f; hidden.alpha = 0f
                return
            }
            shown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
            hidden.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(120).start()
        }
        if (send) swap(sendIcon, micIcon) else swap(micIcon, sendIcon)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onActionTouch(event: MotionEvent): Boolean {
        if (sendMode || (recording && locked)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!voice.begin()) return false
                pressedAt = SystemClock.elapsedRealtime()
                gesture = RecordGesture(dpf(110f), dpf(72f), if (rtl) -1 else 1).also { it.press(event.rawX, event.rawY) }
                parent?.requestDisallowInterceptTouchEvent(true)
                showRecording(true)
                UiSounds.play(context, UiCue.RECORD_START)
                action.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val g = gesture ?: return false
                val entered = g.move(event.rawX, event.rawY)
                hint.translationX = -g.cancelProgress * dpf(48f) * (if (rtl) -1 else 1)
                hint.alpha = 1f - g.cancelProgress * 0.6f
                lockPill.translationY = -g.lockProgress * dpf(24f)
                if (entered == RecordGesture.State.CANCEL_ARMED) { hint.setTextColor(context.color(R.color.negative)); tickHaptic(reject = true) }
                if (entered == RecordGesture.State.HOLDING) hint.setTextColor(context.color(R.color.text_secondary))
                if (entered == RecordGesture.State.LOCKED) { tickHaptic(reject = false); enterLockedUi() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val g = gesture ?: return false
                val held = SystemClock.elapsedRealtime() - pressedAt
                when (if (event.actionMasked == MotionEvent.ACTION_CANCEL) RecordGesture.Outcome.CANCEL else g.release()) {
                    RecordGesture.Outcome.SEND -> finish(send = true, heldMs = held)
                    RecordGesture.Outcome.CANCEL -> finish(send = false, heldMs = held)
                    RecordGesture.Outcome.KEEP -> Unit
                }
                return true
            }
        }
        return false
    }

    private fun tickHaptic(reject: Boolean) {
        val constant = when {
            Build.VERSION.SDK_INT >= 30 -> if (reject) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        action.performHapticFeedback(constant)
    }

    private fun enterLockedUi() {
        hint.visibility = View.GONE
        lockPill.visibility = View.GONE
        trash.visibility = View.VISIBLE
        dot.visibility = View.GONE
        sendIcon.alpha = 1f; sendIcon.scaleX = 1f; sendIcon.scaleY = 1f
        micIcon.alpha = 0f
        action.contentDescription = context.getString(R.string.voice_send)
        action.scaleX = 1f; action.scaleY = 1f
    }

    private fun finish(send: Boolean, heldMs: Long = SystemClock.elapsedRealtime() - pressedAt) {
        if (!recording) return
        showRecording(false)
        if (!send) UiSounds.play(context, UiCue.RECORD_CANCEL)
        voice.end(send, heldMs)
    }

    /** Called by the screen when the recorder stops on its own (maximum length, failure). */
    fun recordingEnded() { if (recording) showRecording(false) }

    private fun showRecording(on: Boolean) {
        recording = on
        pulse?.cancel()
        if (on) {
            shownSeconds = -1
            timer.text = "0:00"
            meter.clear()
            hint.visibility = View.VISIBLE
            hint.translationX = 0f; hint.alpha = 1f
            hint.setTextColor(context.color(R.color.text_secondary))
            trash.visibility = View.GONE
            dot.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            lockPill.visibility = View.VISIBLE
            lockPill.translationY = 0f
            micIcon.alpha = 1f
            action.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start()
            if (ValueAnimator.areAnimatorsEnabled()) {
                pulse = ValueAnimator.ofFloat(1f, 0.25f).apply {
                    duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { dot.alpha = it.animatedValue as Float }
                    start()
                }
            }
            postOnAnimation(tick)
        } else {
            bar.visibility = View.GONE
            lockPill.visibility = View.GONE
            action.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            gesture?.reset()
            gesture = null
            renderActionMode(animate = false)
            removeCallbacks(tick)
        }
    }

    override fun onDetachedFromWindow() { pulse?.cancel(); removeCallbacks(tick); super.onDetachedFromWindow() }
}
