package app.line.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.R
import app.line.core.TransferStage
import app.line.ui.Dimens
import app.line.ui.IconView
import app.line.ui.ProgressRing
import app.line.ui.TextStyle
import app.line.ui.accentGradient
import app.line.ui.circle
import app.line.ui.color
import app.line.ui.dp
import app.line.ui.dpf
import app.line.ui.label
import app.line.ui.roundRect
import app.line.ui.withAlpha
import kotlin.math.abs
import kotlin.math.max

/** Live playback of one voice message, as the bubble draws it. */
data class Playback(val playing: Boolean, val preparing: Boolean, val positionMs: Int, val durationMs: Int, val speed: Float)

/** Bars of a voice message; the played part is highlighted. Tap or drag to seek; the seek is committed on release. */
class WaveformView(context: Context) : View(context) {
    var onSeek: ((Float) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var idleColor = context.color(R.color.text_tertiary)
    var playedColor = context.color(R.color.accent)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val barWidth = dpf(2f)
    private val gap = dpf(2f)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var source: ByteArray? = null
    private var bars = IntArray(0)
    private var progress = 0f
    private var scrub: Float? = null
    private var downX = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable { longPressed = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPress?.invoke() }

    fun setWaveform(bytes: ByteArray?) {
        if (bytes === source) return
        source = bytes
        bars = IntArray(0)
        invalidate()
    }

    fun setProgress(value: Float) {
        if (value == progress) return
        progress = value
        if (scrub == null) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val count = max(1, ((width + gap) / (barWidth + gap)).toInt())
        if (bars.size != count) bars = VoiceMath.bars(source, count)
        val played = VoiceMath.playedBars(scrub ?: progress, count)
        val minHeight = dpf(2f)
        val usable = height.toFloat()
        val step = if (count > 1) (width - barWidth) / (count - 1) else 0f
        for (index in 0 until count) {
            val barHeight = max(minHeight, bars[index] / 100f * usable)
            val left = index * step
            rect.set(left, (usable - barHeight) / 2f, left + barWidth, (usable + barHeight) / 2f)
            paint.color = if (index < played) playedColor else idleColor
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; dragging = false; longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) { scrub = VoiceMath.fraction(event.x, 0f, width.toFloat()); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!longPressed) onSeek?.invoke(VoiceMath.fraction(event.x, 0f, width.toFloat()))
                scrub = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); scrub = null; invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() { removeCallbacks(longPress); super.onDetachedFromWindow() }
}

/** Round play/pause button; shows a transfer ring in place of the glyph while the audio is on its way. */
class PlayButton(context: Context) : FrameLayout(context) {
    private val glyph = IconView(context, "play", R.color.on_accent)
    private val ring = ProgressRing(context).apply { visibility = View.GONE }

    init {
        addView(glyph, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = true
    }

    fun style(outgoing: Boolean) {
        background = if (outgoing) context.circle(R.color.on_accent) else context.accentGradient(24).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        glyph.setTint(if (outgoing) R.color.accent else R.color.on_accent)
    }

    fun showGlyph(name: String, description: String) {
        ring.visibility = View.GONE
        glyph.visibility = View.VISIBLE
        glyph.setIcon(name)
        contentDescription = description
    }

    fun showRing(progress: Float?, icon: String, description: String) {
        glyph.visibility = View.GONE
        ring.visibility = View.VISIBLE
        ring.set(progress, icon)
        contentDescription = description
    }
}

/** Data a voice bubble needs, resolved by the adapter. */
class VoiceModel(
    val id: String,
    val waveform: ByteArray?,
    val durationMs: Long,
    val outgoing: Boolean,
    val played: Boolean,
    val stage: TransferStage,
    val progress: Float,
)

/** Voice bubble content: play button, waveform, elapsed time, unplayed dot, speed chip. */
class VoiceContent(context: Context, private val format: ChatFormat) : LinearLayout(context) {
    val play = PlayButton(context)
    val wave = WaveformView(context)
    val speed: TextView = context.label("1×", TextStyle.CAPTION_STRONG).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(2), dp(8), dp(2))
        visibility = View.GONE
        isClickable = true
        minHeight = dp(24)
    }
    private val time = context.label("0:00", TextStyle.CAPTION_STRONG)
    private val dot = View(context).apply { visibility = View.GONE }
    private var model: VoiceModel? = null
    private var outgoing = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(play, LayoutParams(dp(48), dp(48)))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(time)
        row.addView(dot, LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8) })
        row.addView(speed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        column.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(272))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    fun bind(model: VoiceModel, playback: Playback?) {
        val sameStyle = this.model?.outgoing == model.outgoing && this.model != null
        this.model = model
        outgoing = model.outgoing
        if (!sameStyle) {
            play.style(outgoing)
            wave.idleColor = context.color(if (outgoing) R.color.bubble_out_meta else R.color.text_tertiary)
            wave.playedColor = context.color(if (outgoing) R.color.bubble_out_text else R.color.accent)
            time.setTextColor(context.color(if (outgoing) R.color.bubble_out_meta else R.color.text_secondary))
            speed.background = context.roundRect(R.color.accent_soft, 12).also {
                if (outgoing) it.setColor(context.color(R.color.on_accent).withAlpha(0.22f))
            }
            speed.setTextColor(context.color(if (outgoing) R.color.bubble_out_text else R.color.accent))
            dot.background = context.circle(R.color.accent)
        }
        wave.setWaveform(model.waveform)
        setPlayback(playback)
    }

    fun setPlayback(playback: Playback?) {
        val model = model ?: return
        val stage = model.stage
        val c = context
        when {
            TransferUi.showsRing(stage) || (!model.outgoing && stage != TransferStage.READY && !TransferUi.isBroken(stage)) ->
                play.showRing(TransferUi.ringProgress(stage, model.progress), if (model.outgoing) "close" else "download", c.getString(R.string.media_downloading))
            stage == TransferStage.FAILED -> play.showGlyph("refresh", c.getString(R.string.media_retry))
            stage == TransferStage.EXPIRED -> play.showGlyph("alert", c.getString(R.string.cv_media_expired))
            playback?.playing == true || playback?.preparing == true -> play.showGlyph("pause", c.getString(R.string.voice_pause))
            else -> play.showGlyph("play", c.getString(R.string.voice_play))
        }
        val active = playback != null && stage == TransferStage.READY
        val total = if (active && playback!!.durationMs > 0) playback.durationMs.toLong() else model.durationMs
        wave.setProgress(if (active && total > 0) playback!!.positionMs.toFloat() / total else 0f)
        time.text = when {
            stage == TransferStage.FAILED -> c.getString(if (model.outgoing) R.string.status_failed else R.string.media_failed)
            stage == TransferStage.EXPIRED -> c.getString(R.string.cv_media_expired)
            active -> VoiceMath.duration(playback!!.positionMs.toLong())
            else -> VoiceMath.duration(total, roundUp = true)
        }
        dot.visibility = if (!model.outgoing && !model.played && stage == TransferStage.READY && !active) View.VISIBLE else View.GONE
        speed.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            speed.text = format.speed(playback!!.speed)
            speed.contentDescription = c.getString(R.string.cv_voice_speed)
        }
    }
}

/** File bubble content: type icon, name, size or state. */
class FileContent(context: Context) : LinearLayout(context) {
    private val tile = FrameLayout(context)
    private val glyph = IconView(context, "file", R.color.accent)
    private val ring = ProgressRing(context).apply { visibility = View.GONE }
    private val name = context.label("", TextStyle.BODY_STRONG, R.color.bubble_in_text, maxLines = 1).apply {
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    private val info = context.label("", TextStyle.CAPTION, R.color.text_secondary, maxLines = 1)
    private var styled: Boolean? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = dp(248)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        tile.addView(glyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        tile.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(tile, LayoutParams(dp(48), dp(48)))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(name)
        column.addView(info, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
    }

    fun bind(outgoing: Boolean, fileName: String, mime: String, sizeText: String, stage: TransferStage, progress: Float) {
        if (styled != outgoing) {
            styled = outgoing
            tile.background = context.roundRect(if (outgoing) R.color.on_accent else R.color.accent_soft, Dimens.RADIUS_M).also {
                if (outgoing) it.setColor(context.color(R.color.on_accent).withAlpha(0.22f))
            }
            glyph.setTint(if (outgoing) R.color.bubble_out_text else R.color.accent)
            name.setTextColor(context.color(if (outgoing) R.color.bubble_out_text else R.color.bubble_in_text))
            info.setTextColor(context.color(if (outgoing) R.color.bubble_out_meta else R.color.text_secondary))
        }
        name.text = fileName
        val notReady = stage != TransferStage.READY
        when {
            TransferUi.showsRing(stage) -> { ring.visibility = View.VISIBLE; ring.set(TransferUi.ringProgress(stage, progress), if (outgoing) "close" else "download") }
            stage == TransferStage.FAILED -> { ring.visibility = View.GONE; glyph.setIcon("refresh") }
            stage == TransferStage.EXPIRED -> { ring.visibility = View.GONE; glyph.setIcon("alert") }
            !outgoing && notReady -> { ring.visibility = View.GONE; glyph.setIcon("download") }
            else -> { ring.visibility = View.GONE; glyph.setIcon(FileKinds.icon(mime)) }
        }
        info.text = when (stage) {
            TransferStage.FAILED -> context.getString(if (outgoing) R.string.status_failed else R.string.media_failed)
            TransferStage.EXPIRED -> context.getString(R.string.cv_media_expired)
            TransferStage.QUEUED -> if (outgoing) sizeText else context.getString(R.string.media_download) + " · " + sizeText
            else -> sizeText
        }
    }
}
