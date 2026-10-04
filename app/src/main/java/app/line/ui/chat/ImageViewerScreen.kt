package app.line.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.line.R
import app.line.ui.MATCH
import app.line.ui.ProgressRing
import app.line.ui.Screen
import app.line.ui.TextStyle
import app.line.ui.WRAP
import app.line.ui.color
import app.line.ui.dp
import app.line.ui.iconButton
import app.line.ui.label
import app.line.ui.roundRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A bitmap you can pinch, pan and double-tap; dragging it down while fitted dismisses the viewer. */
class ZoomImageView(context: Context) : View(context) {
    var onDismiss: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val rect = RectF()
    private var bitmap: Bitmap? = null
    private var baseScale = 1f
    private var dragY = 0f
    private var dragging = false
    private var animator: ValueAnimator? = null

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (scale() * detector.scaleFactor).coerceIn(baseScale * 0.85f, baseScale * MAX_ZOOM)
            val factor = target / scale()
            matrix.postScale(factor, factor, detector.focusX, detector.focusY)
            clamp()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) { if (scale() < baseScale) animateTo(baseScale, width / 2f, height / 2f) }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress) return false
            if (isZoomed()) {
                matrix.postTranslate(-dx, -dy)
                clamp()
                invalidate()
            } else {
                dragging = true
                dragY -= dy
                translationY = dragY
                onDrag?.invoke((abs(dragY) / (height * 0.4f)).coerceIn(0f, 1f))
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateTo(if (isZoomed()) baseScale else baseScale * DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }
    })

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        fit()
        invalidate()
    }

    private fun scale(): Float { matrix.getValues(values); return values[Matrix.MSCALE_X] }
    private fun isZoomed() = scale() > baseScale * 1.02f

    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        baseScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.setScale(baseScale, baseScale)
        matrix.postTranslate((width - b.width * baseScale) / 2f, (height - b.height * baseScale) / 2f)
    }

    private fun clamp() {
        val b = bitmap ?: return
        rect.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        matrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) dx = (width - rect.width()) / 2f - rect.left
        else if (rect.left > 0f) dx = -rect.left else if (rect.right < width) dx = width - rect.right
        if (rect.height() <= height) dy = (height - rect.height()) / 2f - rect.top
        else if (rect.top > 0f) dy = -rect.top else if (rect.bottom < height) dy = height - rect.bottom
        matrix.postTranslate(dx, dy)
    }

    private fun animateTo(target: Float, focusX: Float, focusY: Float) {
        animator?.cancel()
        val start = scale()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (ValueAnimator.areAnimatorsEnabled()) 200 else 0
            addUpdateListener {
                val next = start + (target - start) * it.animatedFraction
                val factor = next / scale()
                matrix.postScale(factor, factor, focusX, focusY)
                clamp()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { fit(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, matrix, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && dragging) {
            dragging = false
            if (abs(dragY) > height * 0.18f) onDismiss?.invoke()
            else {
                animate().translationY(0f).setDuration(160).start()
                onDrag?.invoke(0f)
            }
            dragY = 0f
        }
        return true
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    private companion object {
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}

/** Full-screen photo: dark, edge to edge, chrome fades with a tap; share and save from the top bar. */
class ImageViewerScreen(private val messageId: String, private val name: String?, private val mime: String) : Screen() {
    override val edgeToEdge = true
    override val modal = true
    override val darkChrome = true

    private lateinit var root: FrameLayout
    private lateinit var zoom: ZoomImageView
    private lateinit var bar: LinearLayout
    private lateinit var ring: ProgressRing
    private lateinit var note: android.widget.TextView
    private var job: Job? = null
    private var file: File? = null
    private var chromeVisible = true

    override fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(context.color(R.color.call_bottom)) }
        zoom = ZoomImageView(context).apply {
            onTap = ::toggleChrome
            onDismiss = { host.pop() }
            onDrag = { progress -> root.background?.alpha = ((1f - progress * 0.7f) * 255).toInt(); bar.alpha = if (chromeVisible) 1f - progress else 0f }
        }
        ring = ProgressRing(context).apply { set(null, "image") }
        note = context.label("", TextStyle.CALLOUT, R.color.on_accent).apply { visibility = View.GONE; gravity = Gravity.CENTER }
        bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(4), context.dp(8), context.dp(4))
            addView(context.iconButton("close", context.getString(R.string.close), tintRes = R.color.on_accent, backgroundRes = R.color.scrim) { host.pop() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(context.iconButton("share", context.getString(R.string.cv_msg_share), tintRes = R.color.on_accent, backgroundRes = R.color.scrim) { act { MediaActions.share(host, it, name, mime, messageId) } })
            addView(context.iconButton("download", context.getString(R.string.msg_save), tintRes = R.color.on_accent, backgroundRes = R.color.scrim) { act { MediaActions.save(host, it, name, mime, messageId) } },
                LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply { marginStart = context.dp(8) })
        }
        root.addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(ring, FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.CENTER))
        root.addView(note, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = context.dp(64) })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        return root
    }

    override fun onInsets(top: Int, bottom: Int) {
        if (isBuilt) bar.setPadding(context.dp(4), top + context.dp(4), context.dp(8), context.dp(4))
    }

    override fun onShown() { if (file == null && job?.isActive != true) load() }

    private fun load() {
        val service = host.service
        if (service == null) return
        job = host.uiScope.launch {
            try {
                val source = service.plainFile(messageId)
                val bitmap = withContext(Dispatchers.Default) { ChatImages.decode(source, MAX_EDGE) }
                ring.visibility = View.GONE
                if (bitmap == null) { note.visibility = View.VISIBLE; note.text = context.getString(R.string.media_unsupported_image) } else {
                    file = source
                    zoom.setBitmap(bitmap)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ring.visibility = View.GONE
                note.visibility = View.VISIBLE
                note.text = context.chatErrorText(e)
            }
        }
    }

    override fun onServiceReady() { if (isBuilt && file == null && job?.isActive != true) load() }

    private fun act(block: suspend (File) -> Unit) {
        val source = file ?: return
        host.run { block(source) }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        bar.visibility = View.VISIBLE
        bar.animate().alpha(if (chromeVisible) 1f else 0f).setDuration(160)
            .withEndAction { bar.visibility = if (chromeVisible) View.VISIBLE else View.INVISIBLE }.start()
    }

    override fun destroy() { job?.cancel() }

    private companion object { const val MAX_EDGE = 2560 }
}
