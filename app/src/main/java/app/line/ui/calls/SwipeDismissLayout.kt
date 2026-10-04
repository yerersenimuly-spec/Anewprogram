package app.line.ui.calls

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Lets the user pull [content] down to dismiss a modal screen. The drag starts only when [atTop] says the
 * content cannot scroll further up, so it never fights a scrolling child.
 */
class SwipeDismissLayout(context: Context, private val content: View, private val atTop: () -> Boolean, private val onDismiss: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    init { addView(content, LayoutParams(app.line.ui.MATCH, app.line.ui.MATCH)) }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false; tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) } }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dy = event.y - downY
                if (!dragging && dy > slop && dy > abs(event.x - downX) * 1.5f && atTop()) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> release()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> follow((event.y - downY - slop).coerceAtLeast(0f))
            MotionEvent.ACTION_UP -> finish(event)
            MotionEvent.ACTION_CANCEL -> { settle(); release() }
        }
        return true
    }

    private fun follow(offset: Float) {
        content.translationY = offset
        content.alpha = (1f - offset / (height.coerceAtLeast(1) * 0.9f)).coerceIn(0.2f, 1f)
    }

    private fun finish(event: MotionEvent) {
        tracker?.computeCurrentVelocity(1000)
        val velocity = tracker?.yVelocity ?: 0f
        val far = content.translationY > height * 0.22f
        val flung = velocity > minFling && content.translationY > dpf(24f)
        release()
        if (far || flung) fling() else settle()
    }

    private fun dpf(value: Float) = value * resources.displayMetrics.density

    private fun fling() {
        if (!ValueAnimator.areAnimatorsEnabled()) { onDismiss(); return }
        content.animate().translationY(height.toFloat()).alpha(0f).setDuration(160)
            .setInterpolator(AccelerateInterpolator(1.4f)).withEndAction { onDismiss() }
    }

    private fun settle() {
        if (!ValueAnimator.areAnimatorsEnabled()) { content.translationY = 0f; content.alpha = 1f; return }
        content.animate().translationY(0f).alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator(1.6f))
    }

    private fun release() {
        dragging = false
        tracker?.recycle(); tracker = null
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
}
