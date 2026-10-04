package app.line.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import app.line.R
import app.line.core.TransferStage
import app.line.ui.ProgressRing
import app.line.ui.TextStyle
import app.line.ui.color
import app.line.ui.dp
import app.line.ui.label
import app.line.ui.roundRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/** A photo drawn center-cropped with anti-aliased rounded corners; the tiny preview shows (smoothed) until the full image arrives. */
class PhotoView(context: Context) : View(context) {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.surface_raised) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fullPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private var thumb: Bitmap? = null
    private var full: Bitmap? = null
    private var fade: ValueAnimator? = null
    private var pathDirty = true

    val hasFull: Boolean get() = full != null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setCorners(tl: Float, tr: Float, br: Float, bl: Float) {
        radii[0] = tl; radii[1] = tl; radii[2] = tr; radii[3] = tr; radii[4] = br; radii[5] = br; radii[6] = bl; radii[7] = bl
        pathDirty = true
        invalidate()
    }

    fun clear() {
        fade?.cancel()
        thumb = null; full = null
        thumbPaint.shader = null; fullPaint.shader = null
        invalidate()
    }

    fun setThumb(bitmap: Bitmap?) {
        if (bitmap === thumb) return
        thumb = bitmap
        thumbPaint.shader = bitmap?.let { shaderFor(it) }
        invalidate()
    }

    fun setFull(bitmap: Bitmap?, animate: Boolean) {
        if (bitmap === full) return
        fade?.cancel()
        full = bitmap
        fullPaint.shader = bitmap?.let { shaderFor(it) }
        if (bitmap != null && animate && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) {
            fullPaint.alpha = 0
            fade = ValueAnimator.ofInt(0, 255).apply {
                duration = 180
                addUpdateListener { fullPaint.alpha = it.animatedValue as Int; invalidate() }
                start()
            }
        } else {
            fullPaint.alpha = 255
        }
        invalidate()
    }

    private fun shaderFor(bitmap: Bitmap): Shader {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        fit(shader, bitmap)
        return shader
    }

    private fun fit(shader: Shader, bitmap: Bitmap) {
        if (width == 0 || height == 0) return
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
        shader.setLocalMatrix(matrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pathDirty = true
        thumb?.let { b -> thumbPaint.shader?.let { fit(it, b) } }
        full?.let { b -> fullPaint.shader?.let { fit(it, b) } }
    }

    override fun onDraw(canvas: Canvas) {
        if (pathDirty) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            path.reset()
            path.addRoundRect(bounds, radii, Path.Direction.CW)
            pathDirty = false
        }
        canvas.drawPath(path, base)
        if (thumb != null && (full == null || fullPaint.alpha < 255)) canvas.drawPath(path, thumbPaint)
        if (full != null) canvas.drawPath(path, fullPaint)
    }

    override fun onDetachedFromWindow() { fade?.cancel(); super.onDetachedFromWindow() }
}

/** Everything a photo bubble needs, resolved by the adapter. */
class PhotoModel(
    val id: String,
    val box: ImageSizing.Box,
    val thumb: ByteArray?,
    val stage: TransferStage,
    val progress: Float,
    val outgoing: Boolean,
    val source: suspend () -> File,
)

/** Photo bubble content: preview first, then the image; a ring while transferring and a note when it broke. */
class PhotoContent(context: Context, private val scope: CoroutineScope) : FrameLayout(context), CornerAware {
    private val photo = PhotoView(context)
    private val ring = ProgressRing(context).apply { visibility = View.GONE }
    private val note = context.label("", TextStyle.CAPTION_STRONG, R.color.on_accent).apply {
        background = context.roundRect(R.color.scrim, 10)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        visibility = View.GONE
    }
    private var boundId: String? = null
    private var loadedStage: TransferStage? = null
    private var job: Job? = null
    private var boxWidth = 0
    private var boxHeight = 0

    init {
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ring, LayoutParams(dp(48), dp(48), Gravity.CENTER))
        addView(note, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(12) })
    }

    override fun setCorners(corners: FloatArray, roundBottom: Boolean) {
        val flat = 0f
        photo.setCorners(corners[0], corners[1], if (roundBottom) corners[2] else flat, if (roundBottom) corners[3] else flat)
    }

    fun bind(model: PhotoModel, noteText: String?) {
        val sameMessage = model.id == boundId
        if (!sameMessage) {
            job?.cancel()
            photo.clear()
            loadedStage = null
            boundId = model.id
        }
        if (boxWidth != model.box.width || boxHeight != model.box.height) {
            boxWidth = model.box.width; boxHeight = model.box.height
            layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(0, 0)).also { it.width = boxWidth; it.height = boxHeight }
        }
        model.thumb?.let { photo.setThumb(ChatImages.thumb(model.id, it)) }
        ChatImages.peek(ChatImages.fullKey(model.id))?.let { photo.setFull(it, animate = false) }
        renderTransfer(model, noteText)
        val wantFull = (model.outgoing || model.stage == TransferStage.READY) && !photo.hasFull && loadedStage != model.stage
        if (wantFull) {
            loadedStage = model.stage
            val edge = (max(boxWidth, boxHeight) * 2).coerceAtMost(1600)
            job?.cancel()
            job = scope.launch {
                val bitmap = try { ChatImages.full(model.id, edge, model.source) } catch (_: Exception) { null }
                if (boundId == model.id && bitmap != null) photo.setFull(bitmap, animate = true)
            }
        }
    }

    private fun renderTransfer(model: PhotoModel, noteText: String?) {
        when {
            TransferUi.showsRing(model.stage) -> {
                ring.visibility = View.VISIBLE
                ring.set(TransferUi.ringProgress(model.stage, model.progress), if (model.outgoing) "close" else "download")
            }
            TransferUi.isBroken(model.stage) -> { ring.visibility = View.VISIBLE; ring.set(1f, if (model.stage == TransferStage.EXPIRED) "alert" else "refresh") }
            !model.outgoing && model.stage != TransferStage.READY -> { ring.visibility = View.VISIBLE; ring.set(null, "download") }
            else -> ring.visibility = View.GONE
        }
        note.visibility = if (noteText != null) View.VISIBLE else View.GONE
        note.text = noteText
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(boxWidth, boxHeight)
        measureChildren(
            MeasureSpec.makeMeasureSpec(boxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(boxHeight, MeasureSpec.AT_MOST),
        )
        photo.measure(MeasureSpec.makeMeasureSpec(boxWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(boxHeight, MeasureSpec.EXACTLY))
    }

    fun recycle() { job?.cancel(); job = null; boundId = null }
}
