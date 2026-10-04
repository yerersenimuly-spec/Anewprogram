package app.line.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.Link
import app.line.R
import app.line.core.DisplayName
import app.line.core.MessageStatus
import java.util.Locale

/** Round avatar: gradient picked deterministically from the number, initials from the name, silhouette otherwise. */
class AvatarView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Fonts.get(context, 600); color = 0xFFFFFFFF.toInt()
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xE6FFFFFF.toInt()
    }
    private var initials = ""
    private var pair = PALETTE[0]
    private var shader: Shader? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bind(number: String, name: String?) {
        pair = PALETTE[Math.floorMod(number.hashCode(), PALETTE.size)]
        initials = if (name.isNullOrBlank()) "" else DisplayName.initials(name)
        shader = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shader = null }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        if (shader == null) shader = LinearGradient(0f, 0f, size, size, pair.first, pair.second, Shader.TileMode.CLAMP)
        fill.shader = shader
        canvas.drawCircle(width / 2f, height / 2f, size / 2f, fill)
        if (initials.isNotEmpty()) {
            text.textSize = size * (if (initials.length > 1) 0.36f else 0.42f)
            val y = height / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(initials, width / 2f, y, text)
        } else {
            val scale = size * 0.5f / 24f
            glyph.strokeWidth = 2f
            canvas.save()
            canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
            canvas.scale(scale, scale)
            IconView.shapesOf("person").forEach { canvas.drawPath(it.first, glyph) }
            canvas.restore()
        }
    }

    companion object {
        private val PALETTE = listOf(
            0xFF5B8DFF.toInt() to 0xFF7A5CFF.toInt(),
            0xFFFF8A5B.toInt() to 0xFFFF5C7A.toInt(),
            0xFF34D399.toInt() to 0xFF0EA5A5.toInt(),
            0xFFF59E0B.toInt() to 0xFFEF4444.toInt(),
            0xFFA855F7.toInt() to 0xFFEC4899.toInt(),
            0xFF22D3EE.toInt() to 0xFF3B82F6.toInt(),
            0xFF84CC16.toInt() to 0xFF22C55E.toInt(),
            0xFFF472B6.toInt() to 0xFFFB923C.toInt(),
        )
    }
}

fun Context.avatar(number: String, name: String?, sizeDp: Int): AvatarView =
    AvatarView(this).apply { bind(number, name); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }

/** Round, tappable icon with a mandatory spoken description. */
fun Context.iconButton(
    icon: String,
    description: String,
    sizeDp: Int = 44,
    iconDp: Int = 24,
    tintRes: Int = R.color.text_primary,
    backgroundRes: Int = 0,
    onClick: () -> Unit,
): FrameLayout = FrameLayout(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    background = ripple(if (backgroundRes != 0) circle(backgroundRes) else null, sizeDp / 2)
    contentDescription = description
    isClickable = true; isFocusable = true
    addView(icon(icon, tintRes, iconDp), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
    setOnClickListener { UiSounds.play(context, UiCue.TAP); onClick() }
    Motion.press(this)
}

/** Compact bar of detail screens: back, optional leading view, title with subtitle, trailing actions. */
class TopBar(context: Context) : LinearLayout(context) {
    val title: TextView = context.label("", TextStyle.SUBTITLE, maxLines = 1)
    val subtitle: TextView = context.label("", TextStyle.CAPTION, R.color.text_secondary, maxLines = 1).apply { visibility = View.GONE }
    private val leading = FrameLayout(context)
    private val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val text = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Dimens.BAR_HEIGHT)
        setPadding(dp(4), 0, dp(8), 0)
        addView(leading, LayoutParams(WRAP, WRAP))
        text.addView(title)
        text.addView(subtitle)
        addView(text, LayoutParams(0, WRAP, 1f))
        addView(actions, LayoutParams(WRAP, WRAP))
    }

    fun back(description: String, onClick: () -> Unit): TopBar = apply {
        leading.removeAllViews()
        leading.addView(context.iconButton("back", description, onClick = onClick))
    }

    fun leading(view: View): TopBar = apply {
        leading.removeAllViews()
        leading.addView(view)
        text.setPadding(dp(4), 0, 0, 0)
    }

    fun setTitle(value: CharSequence?): TopBar = apply { title.text = value }
    fun setSubtitle(value: CharSequence?): TopBar = apply {
        subtitle.text = value
        subtitle.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun clearActions(): TopBar = apply { actions.removeAllViews() }
    fun action(icon: String, description: String, onClick: () -> Unit): TopBar = apply {
        actions.addView(context.iconButton(icon, description, onClick = onClick))
    }
}

/** Title bar of the three root tabs. */
class LargeTitleBar(context: Context, titleText: CharSequence) : LinearLayout(context) {
    val title: TextView = context.label(titleText, TextStyle.HEADLINE, maxLines = 1)
    private val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(Dimens.SCREEN_PADDING), dp(4), dp(10), dp(4))
        addView(title, LayoutParams(0, WRAP, 1f))
        addView(actions, LayoutParams(WRAP, WRAP))
    }

    fun action(icon: String, description: String, onClick: () -> Unit): LargeTitleBar = apply {
        actions.addView(context.iconButton(icon, description, onClick = onClick))
    }
}

data class NavItem(val id: String, val icon: String, val label: Int)

/** Bottom navigation of the three root tabs, with an optional unread badge. */
class BottomNav(context: Context, private val items: List<NavItem>, private val onSelect: (String) -> Unit) : LinearLayout(context) {
    private val pills = HashMap<String, View>()
    private val icons = HashMap<String, IconView>()
    private val labels = HashMap<String, TextView>()
    private val badges = HashMap<String, TextView>()
    private var selected = ""

    init {
        orientation = HORIZONTAL
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(context.color(R.color.outline)),
            android.graphics.drawable.ColorDrawable(context.color(R.color.surface)),
        )).apply { setLayerInset(1, 0, 1, 0, 0) }
        setPadding(dp(8), dp(8), dp(8), dp(6))
        items.forEach { item -> addView(build(item), LayoutParams(0, WRAP, 1f)) }
    }

    private fun build(item: NavItem): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        contentDescription = context.getString(item.label)
        isClickable = true; isFocusable = true
        val holder = FrameLayout(context)
        val pill = View(context).apply { background = context.roundRect(R.color.accent_soft, 16); alpha = 0f }
        val glyph = context.icon(item.icon, R.color.text_tertiary, 24)
        val badge = context.label("", TextStyle.MICRO, R.color.on_accent).apply {
            background = context.roundRect(R.color.accent, 9); gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(5), dp(2), dp(5), dp(2)); minWidth = dp(18)
        }
        holder.addView(pill, FrameLayout.LayoutParams(dp(60), dp(32), Gravity.CENTER))
        holder.addView(glyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        holder.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { marginEnd = dp(2); topMargin = dp(-4) })
        val caption = context.label(context.getString(item.label), TextStyle.MICRO, R.color.text_tertiary)
        addView(holder, LayoutParams(dp(60), dp(32)))
        addView(caption, LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        pills[item.id] = pill; icons[item.id] = glyph; labels[item.id] = caption; badges[item.id] = badge
        setOnClickListener { UiSounds.play(context, UiCue.TAP); onSelect(item.id) }
    }

    fun select(id: String) {
        if (id == selected) return
        selected = id
        items.forEach { item ->
            val active = item.id == id
            pills[item.id]?.animate()?.alpha(if (active) 1f else 0f)?.setDuration(160)?.start()
            icons[item.id]?.setTint(if (active) R.color.accent else R.color.text_tertiary)
            labels[item.id]?.setTextColor(context.color(if (active) R.color.accent else R.color.text_tertiary))
        }
    }

    fun badge(id: String, count: Int) {
        val badge = badges[id] ?: return
        badge.text = if (count > 99) "99+" else count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }
}

fun Context.divider(insetStartDp: Int = 0): View = View(this).apply {
    setBackgroundColor(color(R.color.outline))
    layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { marginStart = dp(insetStartDp) }
}

fun Context.sectionHeader(text: CharSequence): TextView =
    label(text.toString().uppercase(Locale.getDefault()), TextStyle.MICRO, R.color.text_tertiary).apply {
        setPadding(dp(Dimens.SCREEN_PADDING), dp(24), dp(Dimens.SCREEN_PADDING), dp(8))
    }

/** A grouped surface: rows inside share one rounded card with inset dividers. */
fun Context.card(): LinearLayout = column {
    background = roundRect(R.color.surface, Dimens.RADIUS_L)
    clipToOutline = true
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dp(16); marginEnd = dp(16) }
}

/** One list row: leading view, title with optional subtitle, trailing view. */
class ListRow(context: Context) : LinearLayout(context) {
    private val leadingHolder = FrameLayout(context)
    private val trailingHolder = FrameLayout(context)
    val title: TextView = context.label("", TextStyle.BODY_STRONG, maxLines = 1)
    val subtitle: TextView = context.label("", TextStyle.CALLOUT, R.color.text_secondary, maxLines = 1).apply { visibility = View.GONE }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(Dimens.SCREEN_PADDING), dp(10), dp(Dimens.SCREEN_PADDING), dp(10))
        val text = LinearLayout(context).apply { orientation = VERTICAL }
        text.addView(title)
        text.addView(subtitle, LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
        addView(leadingHolder, LayoutParams(WRAP, WRAP))
        addView(text, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        addView(trailingHolder, LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        background = context.ripple(null)
        isClickable = true; isFocusable = true
    }

    fun leading(view: View?): ListRow = apply {
        leadingHolder.removeAllViews()
        if (view != null) leadingHolder.addView(view)
        (text().layoutParams as LayoutParams).marginStart = if (view == null) 0 else dp(14)
    }

    fun trailing(view: View?): ListRow = apply {
        trailingHolder.removeAllViews()
        if (view != null) trailingHolder.addView(view)
    }

    fun subtitle(value: CharSequence?): ListRow = apply {
        subtitle.text = value
        subtitle.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun text(): View = getChildAt(1)

    fun onClick(action: () -> Unit): ListRow = apply { setOnClickListener { action() } }
}

fun Context.tintedIcon(icon: String, tintRes: Int = R.color.accent, backgroundRes: Int = R.color.accent_soft): FrameLayout = FrameLayout(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    background = roundRect(backgroundRes, 11)
    addView(icon(icon, tintRes, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
}

/** Centered placeholder for empty lists and error states. */
fun Context.emptyState(icon: String, title: CharSequence, body: CharSequence?, actionLabel: CharSequence? = null, onAction: (() -> Unit)? = null): LinearLayout = column {
    gravity = Gravity.CENTER
    setPadding(dp(40), dp(24), dp(40), dp(24))
    addView(FrameLayout(context).apply {
        background = circle(R.color.surface_raised)
        addView(icon(icon, R.color.text_secondary, 30), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
    }, LinearLayout.LayoutParams(dp(76), dp(76)))
    addView(label(title, TextStyle.TITLE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20) })
    if (!body.isNullOrEmpty()) addView(label(body, TextStyle.CALLOUT, R.color.text_secondary).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
    if (actionLabel != null && onAction != null) addView(primaryButton(actionLabel, onAction),
        LinearLayout.LayoutParams(WRAP, dp(48)).apply { topMargin = dp(24) })
}

/** Slim status strip under the title: appears only when the connection is not fully usable. */
class ConnectionBanner(context: Context) : FrameLayout(context) {
    private val text = context.label("", TextStyle.CAPTION_STRONG, R.color.text_primary)
    private val dot = View(context)
    private var pulse: ValueAnimator? = null

    init {
        visibility = View.GONE
        val line = context.row { setPadding(dp(Dimens.SCREEN_PADDING), dp(9), dp(Dimens.SCREEN_PADDING), dp(9)) }
        line.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
        line.addView(text)
        addView(line, LayoutParams(MATCH, WRAP))
    }

    fun bind(link: Link, pending: Int, hasConfig: Boolean) {
        val show = hasConfig && link != Link.ONLINE
        if (!show) { hide(); return }
        val waiting = link == Link.WAITING_NETWORK
        text.text = context.getString(if (waiting) R.string.banner_waiting_network else R.string.banner_connecting) +
            if (pending > 0) " · " + context.getString(R.string.banner_unsent, pending) else ""
        setBackgroundColor(context.color(if (waiting) R.color.surface_raised else R.color.accent_soft))
        dot.background = context.circle(if (waiting) R.color.warning else R.color.accent)
        if (visibility != View.VISIBLE) { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(180).start() }
        if (pulse == null) pulse = ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun hide() {
        pulse?.cancel(); pulse = null
        if (visibility == View.VISIBLE) animate().alpha(0f).setDuration(160).withEndAction { visibility = View.GONE }.start()
    }

    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; super.onDetachedFromWindow() }
}

/** Circular transfer indicator: determinate arc, or spinning when the size is not known yet. */
class ProgressRing(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55FFFFFF; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFFFFFFFF.toInt()
    }
    private val bounds = RectF()
    private var fraction: Float? = 0f
    private var spin = 0f
    private var iconName = "close"
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    fun set(progress: Float?, icon: String = "close") {
        fraction = progress
        iconName = icon
        if (progress == null) { if (!spinner.isStarted) spinner.start() } else spinner.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = size * 0.07f
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        canvas.drawCircle(width / 2f, height / 2f, size / 2f, bg)
        val inset = size * 0.14f
        bounds.set((width - size) / 2f + inset, (height - size) / 2f + inset, (width + size) / 2f - inset, (height + size) / 2f - inset)
        canvas.drawArc(bounds, 0f, 360f, false, track)
        val sweep = (fraction ?: 0.25f).coerceIn(0.02f, 1f) * 360f
        canvas.drawArc(bounds, -90f + (if (fraction == null) spin else 0f), sweep, false, arc)
        val scale = size * 0.42f / 24f
        glyph.strokeWidth = 2.2f
        canvas.save()
        canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
        canvas.scale(scale, scale)
        IconView.shapesOf(iconName).forEach { canvas.drawPath(it.first, glyph) }
        canvas.restore()
    }

    override fun onDetachedFromWindow() { spinner.cancel(); super.onDetachedFromWindow() }
}

/** Delivery state of an outgoing message: clock, one tick, two ticks, two accent ticks, or an error mark. */
class StatusTicks(context: Context) : FrameLayout(context) {
    private val glyph = IconView(context, "clock", R.color.text_tertiary, 2.2f)

    init { addView(glyph, LayoutParams(dp(16), dp(16), Gravity.CENTER)) }

    fun bind(status: MessageStatus, onBubble: Boolean) {
        val muted = if (onBubble) R.color.bubble_out_meta else R.color.text_tertiary
        val (name, tint, description) = when (status) {
            MessageStatus.PENDING -> Triple("clock", muted, R.string.status_pending)
            MessageStatus.SENT -> Triple("check", muted, R.string.status_sent)
            MessageStatus.DELIVERED -> Triple("check_double", muted, R.string.status_delivered)
            MessageStatus.READ -> Triple("check_double", if (onBubble) R.color.tick_read else R.color.accent, R.string.status_read)
            MessageStatus.FAILED -> Triple("alert", R.color.negative, R.string.status_failed)
            MessageStatus.RECEIVED -> Triple("check", muted, R.string.status_sent)
        }
        glyph.setIcon(name)
        glyph.setTint(tint)
        contentDescription = context.getString(description)
    }
}

/** Placeholder rows shown while a list is loading. */
class SkeletonList(context: Context, rows: Int = 7) : LinearLayout(context) {
    private val animator = ValueAnimator.ofFloat(0.45f, 1f).apply {
        duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    init {
        orientation = VERTICAL
        repeat(rows) {
            addView(context.row {
                setPadding(dp(Dimens.SCREEN_PADDING), dp(12), dp(Dimens.SCREEN_PADDING), dp(12))
                addView(View(context).apply { background = context.circle(R.color.surface_raised) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(context.column {
                    addView(View(context).apply { background = context.roundRect(R.color.surface_raised, 6) }, LinearLayout.LayoutParams(dp(140 + (it * 37) % 80), dp(13)))
                    addView(View(context).apply { background = context.roundRect(R.color.surface_raised, 6) },
                        LinearLayout.LayoutParams(dp(200 + (it * 53) % 90), dp(12)).apply { topMargin = dp(10) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
            })
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}

fun ViewGroup.addFill(view: View) = addView(view, ViewGroup.LayoutParams(MATCH, MATCH))
