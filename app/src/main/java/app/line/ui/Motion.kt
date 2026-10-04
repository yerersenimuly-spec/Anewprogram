package app.line.ui

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

object Motion {
    private const val ENTER_OFFSET_DP = 10f
    private const val PRESS_SCALE = 0.98f
    private const val PRESS_DURATION_MS = 110L
    private const val CHANGE_DURATION_MS = 80L

    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val pressListener = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                        .setDuration(PRESS_DURATION_MS).setInterpolator(easing)
                } else {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(PRESS_DURATION_MS).setInterpolator(easing)
                } else {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }
        }
        false
    }

    fun enter(view: View, duration: Long = 160L) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }

        view.alpha = 0f
        view.translationY = ENTER_OFFSET_DP * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f)
            .setDuration(duration.coerceAtLeast(0L)).setInterpolator(easing)
    }

    fun press(view: View) {
        view.setOnTouchListener(pressListener)
    }

    fun change(content: View, action: () -> Unit) {
        content.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            action()
            content.alpha = 1f
            return
        }

        content.animate().alpha(0f).setDuration(CHANGE_DURATION_MS).setInterpolator(easing)
            .withEndAction {
                try {
                    action()
                } finally {
                    content.animate().alpha(1f).setDuration(CHANGE_DURATION_MS)
                        .setInterpolator(easing)
                }
            }
    }
}
