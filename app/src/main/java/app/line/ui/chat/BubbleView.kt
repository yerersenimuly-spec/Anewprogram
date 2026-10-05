package app.line.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.Spanned
import android.text.style.URLSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.R
import app.line.core.MessageStatus
import app.line.ui.Dimens
import app.line.ui.Motion
import app.line.ui.StatusTicks
import app.line.ui.TextStyle
import app.line.ui.color
import app.line.ui.dp
import app.line.ui.dpf
import app.line.ui.label
import app.line.ui.roundRect
import app.line.ui.style
import app.line.ui.withAlpha
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Reactions to touches on a bubble; the adapter installs one listener per holder. */
interface BubbleListener {
    fun onTap()
    fun onLongPress()
    fun onLink(url: String)
}

/**
 * One message bubble: optional media on top, text, and a time + delivery marker that sits on the last line when it
 * fits. Draws its own rounded (gradient or flat) background so scrolling never allocates.
 */
class BubbleView(context: Context) : ViewGroup(context) {
    enum class MetaMode {
        /** Inline after the last text line, or on its own row. */
        INLINE,

        /** On a dark pill over a photo that has no caption. */
        PILL,

        /** Free-floating in the bottom corner; the media content leaves room for it. */
        PLAIN,
    }

    val media = FrameLayout(context)
    val text: TextView = TextView(context).style(TextStyle.BODY, R.color.bubble_in_text)
    private val time = context.label("", TextStyle.CAPTION, R.color.bubble_in_meta)
    private val ticks = StatusTicks(context)
    private val meta = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(time)
        addView(ticks, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(2) })
    }

    var listener: BubbleListener? = null
    var outgoing = false
        private set
    private var first = true
    private var metaMode = MetaMode.INLINE
    private var pillBackground = context.roundRect(R.color.scrim, Dimens.RADIUS_S)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private var shapeDirty = true
    private var flashAnimator: ValueAnimator? = null
    private var flashLevel = 0f

    /** Corner radii of the bubble in LTR order (tl, tr, br, bl), shared with the photo so it rounds identically. */
    val corners = FloatArray(4)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val url = linkAt(e.x, e.y)
            if (url != null) listener?.onLink(url) else listener?.onTap()
            return true
        }
        override fun onLongPress(e: MotionEvent) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); listener?.onLongPress() }
    })

    init {
        setWillNotDraw(false)
        text.setPadding(0, 0, 0, 0)
        text.isClickable = false
        text.isLongClickable = false
        text.isFocusable = false
        addView(media)
        addView(text)
        addView(meta)
        stroke.color = context.color(R.color.outline)
        stroke.strokeWidth = dpf(1f)
        isClickable = true
        Motion.press(this)
    }

    fun bindShape(outgoing: Boolean, first: Boolean) {
        if (outgoing != this.outgoing || first != this.first || shapeDirty) {
            this.outgoing = outgoing
            this.first = first
            shapeDirty = true
            val bodyColor = if (outgoing) R.color.bubble_out_text else R.color.bubble_in_text
            text.setTextColor(context.color(bodyColor))
            text.setLinkTextColor(context.color(if (outgoing) R.color.bubble_out_text else R.color.accent))
            invalidate()
            requestLayout()
        }
    }

    fun bindMeta(timeText: String, status: MessageStatus?, mode: MetaMode) {
        metaMode = mode
        time.text = timeText
        val onPill = mode == MetaMode.PILL
        time.setTextColor(context.color(when {
            onPill -> R.color.on_accent
            outgoing -> R.color.bubble_out_meta
            else -> R.color.bubble_in_meta
        }))
        meta.background = if (onPill) pillBackground else null
        meta.setPadding(if (onPill) dp(8) else 0, if (onPill) dp(4) else 0, if (onPill) dp(8) else 0, if (onPill) dp(4) else 0)
        if (status != null) { ticks.visibility = View.VISIBLE; ticks.bind(status, onBubble = true) } else ticks.visibility = View.GONE
    }

    fun bindBody(body: CharSequence?) {
        if (body.isNullOrEmpty()) { text.visibility = View.GONE; text.text = null } else {
            text.visibility = View.VISIBLE
            if (text.text !== body) text.text = body
        }
    }

    fun showMedia(visible: Boolean) { media.visibility = if (visible) View.VISIBLE else View.GONE }

    /** Brief wash over the bubble, used to point at a message reached from search. */
    fun highlight() {
        flashAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        flashAnimator = ValueAnimator.ofFloat(0.35f, 0f).apply {
            duration = 1100
            addUpdateListener { flashLevel = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun clearTransient() { flashAnimator?.cancel(); flashLevel = 0f }

    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val padH get() = dp(12)
    private val padV get() = dp(8)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val limit = MeasureSpec.getSize(widthSpec)
        val maxWidth = min((limit * 0.82f).toInt(), dp(440)).coerceAtLeast(dp(120))
        val hasMedia = media.visibility != View.GONE
        val hasText = text.visibility != View.GONE
        meta.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val metaW = meta.measuredWidth
        val metaH = meta.measuredHeight

        var width = 0
        var height = 0
        if (hasMedia) {
            media.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            width = media.measuredWidth
            height = media.measuredHeight
        }
        var inline = false
        if (hasText) {
            val textMax = (if (hasMedia) width else maxWidth) - 2 * padH
            text.measure(MeasureSpec.makeMeasureSpec(textMax, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            val layout: Layout? = text.layout
            var contentW = text.measuredWidth
            if (layout != null && layout.lineCount > 0 && metaMode == MetaMode.INLINE) {
                val last = layout.lineCount - 1
                val lastWidth = ceil(layout.getLineWidth(last)).toInt()
                val bidi = layout.getParagraphDirection(last) == Layout.DIR_RIGHT_TO_LEFT
                inline = !bidi && lastWidth + dp(8) + metaW <= textMax
                contentW = if (inline) max(contentW, lastWidth + dp(8) + metaW) else max(contentW, metaW)
            } else {
                contentW = max(contentW, metaW)
            }
            height += padV + text.measuredHeight + (if (inline || metaMode != MetaMode.INLINE) 0 else metaH) + padV
            width = max(width, contentW + 2 * padH)
        } else if (!hasMedia) {
            width = metaW + 2 * padH
            height = metaH + 2 * padV
        }
        textInline = inline
        if (hasMedia && media.measuredWidth != width) {
            media.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(media.measuredHeight, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height)
    }

    private var textInline = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        var y = 0
        if (media.visibility != View.GONE) {
            media.layout(0, 0, media.measuredWidth, media.measuredHeight)
            y = media.measuredHeight
        }
        if (text.visibility != View.GONE) {
            val x = if (rtl) width - padH - text.measuredWidth else padH
            text.layout(x, y + padV, x + text.measuredWidth, y + padV + text.measuredHeight)
        }
        val inset = when (metaMode) {
            MetaMode.PILL -> dp(8)
            MetaMode.PLAIN -> dp(12)
            MetaMode.INLINE -> padH
        }
        val bottom = when (metaMode) {
            MetaMode.PILL -> dp(8)
            MetaMode.PLAIN -> dp(8)
            MetaMode.INLINE -> padV
        }
        val metaLeft = if (rtl) inset else width - inset - meta.measuredWidth
        val metaBottom = height - bottom
        meta.layout(metaLeft, metaBottom - meta.measuredHeight, metaLeft + meta.measuredWidth, metaBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shapeDirty = true }

    private fun rebuildShape() {
        val big = dpf(Dimens.RADIUS_L.toFloat())
        val tight = dpf(4f)
        val tightRight = outgoing != rtl
        val tl = if (tightRight || first) big else tight
        val tr = if (!tightRight || first) big else tight
        val br = if (tightRight) tight else big
        val bl = if (tightRight) big else tight
        corners[0] = tl; corners[1] = tr; corners[2] = br; corners[3] = bl
        radii[0] = tl; radii[1] = tl; radii[2] = tr; radii[3] = tr; radii[4] = br; radii[5] = br; radii[6] = bl; radii[7] = bl
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        path.reset()
        path.addRoundRect(bounds, radii, Path.Direction.CW)
        if (outgoing) {
            fill.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                context.color(R.color.bubble_out_start), context.color(R.color.bubble_out_end), Shader.TileMode.CLAMP)
        } else {
            fill.shader = null
            fill.color = context.color(R.color.bubble_in)
        }
        flash.color = context.color(R.color.on_accent)
        shapeDirty = false
        (media.getChildAt(0) as? CornerAware)?.setCorners(corners, roundBottom = text.visibility == View.GONE)
    }

    override fun onDraw(canvas: Canvas) {
        if (shapeDirty) rebuildShape()
        canvas.drawPath(path, fill)
        if (!outgoing) canvas.drawPath(path, stroke)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (flashLevel > 0f) {
            flash.alpha = (flashLevel * 255).toInt()
            canvas.drawPath(path, flash)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    private fun linkAt(x: Float, y: Float): String? {
        if (text.visibility == View.GONE) return null
        val spanned = text.text as? Spanned ?: return null
        val layout = text.layout ?: return null
        val localX = x - text.left
        val localY = y - text.top
        if (localY < 0 || localY > text.height || localX < 0 || localX > text.width) return null
        val line = layout.getLineForVertical(localY.toInt())
        if (localX < layout.getLineLeft(line) || localX > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, localX)
        return spanned.getSpans(offset, offset, URLSpan::class.java).firstOrNull()?.url
    }

    override fun onDetachedFromWindow() { clearTransient(); super.onDetachedFromWindow() }
}

/** Media content that rounds itself to the bubble corners (clipping a path is not anti-aliased on hardware canvases). */
interface CornerAware {
    fun setCorners(corners: FloatArray, roundBottom: Boolean)
}
