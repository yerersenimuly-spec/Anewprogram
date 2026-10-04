package app.line.ui.calls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.line.R
import app.line.ui.*

/** Colours of the always-dark call surfaces, derived from the theme tokens so both themes stay in sync. */
object CallPalette {
    fun backdrop(context: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(context.color(R.color.call_top), context.color(R.color.call_bottom)),
    )

    /** Text on the dark surfaces: full white, or softened for secondary lines. */
    fun primary(context: Context): Int = context.color(R.color.on_accent)
    fun secondary(context: Context): Int = primary(context).withAlpha(0.68f)
    fun tertiary(context: Context): Int = primary(context).withAlpha(0.5f)
    fun glass(context: Context): Int = primary(context).withAlpha(0.14f)
    fun glassPressed(context: Context): Int = primary(context).withAlpha(0.22f)

    /** Soft light behind an avatar, fading to nothing at the edge. */
    fun glow(context: Context, color: Int, strength: Float): GradientDrawable = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(color.withAlpha(strength), color.withAlpha(strength * 0.35f), color.withAlpha(0f))
        setGradientCenter(0.5f, 0.5f)
        gradientRadius = context.dpf(160f)
    }
}

enum class RoundKind { GLASS, END, ANSWER }

/** Large round control of the call screens. Toggle buttons invert when active. */
class RoundButton(context: Context, icon: String, description: String, private val kind: RoundKind, sizeDp: Int) : FrameLayout(context) {
    private val glyph = IconView(context, icon, R.color.on_accent)
    private var active = false

    init {
        contentDescription = description
        isClickable = true; isFocusable = true
        addView(glyph, LayoutParams(dp(sizeDp * 2 / 5), dp(sizeDp * 2 / 5), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        paint()
        Motion.press(this)
    }

    fun setIcon(name: String) = glyph.setIcon(name)

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        paint()
    }

    fun setAvailable(value: Boolean) {
        isEnabled = value
        alpha = if (value) 1f else 0.4f
    }

    private fun paint() {
        val fill = when {
            kind == RoundKind.END -> R.color.negative
            kind == RoundKind.ANSWER -> R.color.positive
            active -> R.color.on_accent
            else -> 0
        }
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (fill != 0) context.color(fill) else CallPalette.glass(context))
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt()) }
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(CallPalette.glassPressed(context)), base, mask,
        )
        glyph.setTint(if (kind == RoundKind.GLASS && active) R.color.call_bottom else R.color.on_accent)
    }
}

/** Rings that expand from the avatar while a call is being set up; a faint static halo otherwise. */
class PulseRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dpf(1.5f) }
    private val colour = context.color(R.color.on_accent)
    private var phase = 0f
    private var pulsing = false
    private var attached = false
    private var inner = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** [innerDp] is the avatar radius: rings start at its edge. */
    fun setInner(innerDp: Float) { inner = dpf(innerDp); invalidate() }

    fun setPulsing(value: Boolean) {
        pulsing = value
        update()
    }

    private fun update() {
        if (pulsing && attached && ValueAnimator.areAnimatorsEnabled()) { if (!animator.isStarted) animator.start() }
        else { animator.cancel(); phase = 0f }
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; update() }
    override fun onDetachedFromWindow() { attached = false; animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f - paint.strokeWidth
        if (outer <= inner) return
        if (!pulsing || !animator.isStarted) {
            paint.color = colour.withAlpha(if (pulsing) 0.22f else 0.10f)
            canvas.drawCircle(cx, cy, inner + (outer - inner) * 0.18f, paint)
            return
        }
        for (i in 0..1) {
            val t = (phase + i * 0.5f) % 1f
            val eased = 1f - (1f - t) * (1f - t)
            paint.color = colour.withAlpha(0.30f * (1f - t))
            canvas.drawCircle(cx, cy, inner + (outer - inner) * eased, paint)
        }
    }
}

/** Overlapping avatars of a group; extra people collapse into a count. */
class AvatarStack(context: Context, private val sizeDp: Int, private val overlapDp: Int, private val maxShown: Int = 4) : LinearLayout(context) {
    init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun bind(numbers: List<String>, nameOf: (String) -> PeerLabel) {
        removeAllViews()
        val shown = if (numbers.size > maxShown) maxShown - 1 else numbers.size
        val ring = dp(2)
        fun holder(content: View, index: Int) {
            val cell = FrameLayout(context).apply {
                background = context.circle(R.color.call_bottom)
                setPadding(ring, ring, ring, ring)
                addView(content, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
            }
            addView(cell, LayoutParams(dp(sizeDp) + ring * 2, dp(sizeDp) + ring * 2).apply { if (index > 0) marginStart = -dp(overlapDp) })
        }
        numbers.take(shown).forEachIndexed { i, number ->
            val label = nameOf(number)
            holder(AvatarView(context).apply { bind(number, label.text.takeIf { label.named }) }, i)
        }
        if (numbers.size > shown) {
            val more = context.label("+${numbers.size - shown}", TextStyle.CALLOUT_STRONG, R.color.on_accent).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CallPalette.glassPressed(context)) }
            }
            holder(more, shown)
        }
    }
}
