package app.line.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.R

/** Toggle with an animated thumb. Tapping it flips the state and reports the new value. */
class LineSwitch(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(dpf(2f), 0f, dpf(1f), 0x33000000) }
    private val rect = RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null
    var onToggle: ((Boolean) -> Unit)? = null
    var checked = false
        private set

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true; isFocusable = true
        setOnClickListener { set(!checked, animate = true); UiSounds.play(context, if (checked) UiCue.TOGGLE_ON else UiCue.TOGGLE_OFF); onToggle?.invoke(checked) }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(30))
    }

    fun set(value: Boolean, animate: Boolean = false) {
        checked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || !isAttachedToWindow) { progress = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 160
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(dp(48), dp(30))

    override fun onDraw(canvas: Canvas) {
        val off = context.color(R.color.surface_press)
        val on = context.color(R.color.accent)
        track.color = blend(off, on, progress)
        rect.set(dpf(1f), dpf(2f), width - dpf(1f), height - dpf(2f))
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, track)
        val radius = rect.height() / 2f - dpf(3f)
        val travel = rect.width() - 2f * (radius + dpf(3f))
        canvas.drawCircle(rect.left + radius + dpf(3f) + travel * progress, height / 2f, radius, thumb)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int) = (((from shr shift) and 0xFF) + ((((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * fraction)).toInt() shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }
}

/** Primary call to action: accent gradient, white label. */
fun Context.primaryButton(text: CharSequence, onClick: () -> Unit): TextView = label(text, TextStyle.BODY_STRONG, R.color.on_accent).apply {
    gravity = Gravity.CENTER
    minHeight = dp(52)
    setPadding(dp(28), 0, dp(28), 0)
    background = ripple(accentGradient(16), 16)
    isClickable = true; isFocusable = true
    setOnClickListener { UiSounds.play(context, UiCue.TAP); onClick() }
    Motion.press(this)
}

fun Context.secondaryButton(text: CharSequence, onClick: () -> Unit): TextView = label(text, TextStyle.BODY_STRONG).apply {
    gravity = Gravity.CENTER
    minHeight = dp(52)
    setPadding(dp(28), 0, dp(28), 0)
    background = ripple(roundRect(R.color.surface_raised, 16), 16)
    isClickable = true; isFocusable = true
    setOnClickListener { UiSounds.play(context, UiCue.TAP); onClick() }
    Motion.press(this)
}

fun Context.textButton(text: CharSequence, colorRes: Int = R.color.accent, onClick: () -> Unit): TextView = label(text, TextStyle.BODY_STRONG, colorRes).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48)
    setPadding(dp(16), 0, dp(16), 0)
    background = ripple(null, 14)
    isClickable = true; isFocusable = true
    setOnClickListener { UiSounds.play(context, UiCue.TAP); onClick() }
}

/** Filled single-line (or multi-line) text input with an inline error line. */
class LineField(context: Context, hint: CharSequence) : LinearLayout(context) {
    val edit = EditText(context)
    private val error = context.label("", TextStyle.CAPTION, R.color.negative).apply { visibility = View.GONE }

    init {
        orientation = VERTICAL
        edit.style(TextStyle.BODY)
        edit.hint = hint
        edit.setHintTextColor(context.color(R.color.text_tertiary))
        edit.background = context.roundRect(R.color.surface_raised, Dimens.RADIUS_M)
        edit.setPadding(dp(16), dp(14), dp(16), dp(14))
        edit.minHeight = dp(52)
        edit.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        edit.imeOptions = EditorInfo.IME_ACTION_DONE
        addView(edit, LayoutParams(MATCH, WRAP))
        addView(error, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); marginStart = dp(4) })
    }

    fun digits(length: Int): LineField = apply {
        edit.inputType = InputType.TYPE_CLASS_NUMBER
        edit.filters = arrayOf(InputFilter.LengthFilter(length))
    }

    fun singleLine(maxLength: Int): LineField = apply {
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        edit.setSingleLine(true)
        edit.filters = arrayOf(InputFilter.LengthFilter(maxLength))
    }

    fun text(): String = edit.text.toString()

    fun setError(message: CharSequence?) {
        error.text = message
        error.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        edit.background = context.roundRect(R.color.surface_raised, Dimens.RADIUS_M, if (message.isNullOrEmpty()) 0 else R.color.negative)
    }
}

fun FrameLayout.centered(view: View, widthDp: Int, heightDp: Int) =
    addView(view, FrameLayout.LayoutParams(context.dp(widthDp), context.dp(heightDp), Gravity.CENTER))
