package app.line.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.line.R

/** Layout rhythm: a 4 dp grid. Radii are used consistently across all components. */
object Dimens {
    const val RADIUS_S = 10
    const val RADIUS_M = 14
    const val RADIUS_L = 20
    const val RADIUS_XL = 28
    const val BAR_HEIGHT = 56
    const val NAV_HEIGHT = 64
    const val SCREEN_PADDING = 20
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density
fun View.dp(value: Int): Int = context.dp(value)
fun View.dpf(value: Float): Float = context.dpf(value)
fun Context.color(id: Int): Int = getColor(id)
fun Int.withAlpha(alpha: Float): Int = Color.argb((alpha * 255f).toInt().coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))

/** Inter, bundled in four weights; falls back to the system sans-serif if a font resource is missing. */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    fun get(context: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        val name = when {
            weight >= 700 -> "inter_bold"
            weight >= 600 -> "inter_semibold"
            weight >= 500 -> "inter_medium"
            else -> "inter_regular"
        }
        val id = context.resources.getIdentifier(name, "font", context.packageName)
        (if (id != 0) ResourcesCompat.getFont(context, id) else null) ?: fallback(weight)
    }

    private fun fallback(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
        else Typeface.create(if (weight >= 500) "sans-serif-medium" else "sans-serif", if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
}

/** The type scale. Sizes in sp; one family, hierarchy through size and weight only. */
enum class TextStyle(val sp: Float, val weight: Int, val tracking: Float = 0f, val lineSp: Float = 0f) {
    DISPLAY(34f, 700, -0.03f, 40f),
    HEADLINE(28f, 700, -0.025f, 34f),
    TITLE(20f, 600, -0.01f, 26f),
    SUBTITLE(17f, 600, -0.005f, 22f),
    BODY(16f, 400, 0f, 22f),
    BODY_STRONG(16f, 500, 0f, 22f),
    CALLOUT(15f, 400, 0f, 20f),
    CALLOUT_STRONG(15f, 500, 0f, 20f),
    CAPTION(13f, 400, 0f, 17f),
    CAPTION_STRONG(13f, 500, 0f, 17f),
    MICRO(11f, 600, 0.06f, 14f),
}

fun TextView.style(style: TextStyle, colorRes: Int = R.color.text_primary): TextView = apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
    typeface = Fonts.get(context, style.weight)
    letterSpacing = style.tracking
    includeFontPadding = false
    setTextColor(context.color(colorRes))
    if (style.lineSp > 0f) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.lineSp, resources.displayMetrics)
        if (Build.VERSION.SDK_INT >= 28) lineHeight = px.toInt()
        else setLineSpacing(px - paint.getFontMetrics(null), 1f)
    }
}

fun Context.label(
    text: CharSequence?,
    style: TextStyle = TextStyle.BODY,
    colorRes: Int = R.color.text_primary,
    maxLines: Int = 0,
): TextView = TextView(this).style(style, colorRes).apply {
    this.text = text
    if (maxLines > 0) { this.maxLines = maxLines; ellipsize = TextUtils.TruncateAt.END }
}

fun Context.roundRect(fillRes: Int, radiusDp: Int, strokeRes: Int = 0, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
    setColor(color(fillRes))
    cornerRadius = dpf(radiusDp.toFloat())
    if (strokeRes != 0) setStroke(dp(strokeDp), color(strokeRes))
}

fun Context.accentGradient(radiusDp: Int, angle: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
    GradientDrawable(angle, intArrayOf(color(R.color.accent_start), color(R.color.accent_end))).apply {
        cornerRadius = dpf(radiusDp.toFloat())
    }

/** Pressed feedback that works on any background: a faint wash of the primary text colour. */
fun Context.ripple(content: Drawable?, radiusDp: Int = 0): Drawable {
    val mask = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dpf(radiusDp.toFloat()) }
    return RippleDrawable(ColorStateList.valueOf(color(R.color.text_primary).withAlpha(0.10f)), content, mask)
}

fun Context.circle(fillRes: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(fillRes)) }

fun View.lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight)

fun Context.column(block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }
fun Context.row(block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block()
}

fun View.padding(horizontalDp: Int, verticalDp: Int) = setPadding(dp(horizontalDp), dp(verticalDp), dp(horizontalDp), dp(verticalDp))
fun View.margin(startDp: Int = 0, topDp: Int = 0, endDp: Int = 0, bottomDp: Int = 0) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.setMargins(dp(startDp), dp(topDp), dp(endDp), dp(bottomDp))
    params.marginStart = dp(startDp); params.marginEnd = dp(endDp)
    layoutParams = params
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun frame(context: Context, block: FrameLayout.() -> Unit = {}): FrameLayout = FrameLayout(context).apply(block)

/** Composes two drawables (background, overlay) without allocating a custom class. */
fun layers(vararg drawables: Drawable): Drawable = LayerDrawable(drawables)
