package app.line.ui.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import app.line.R
import app.line.ui.color
import app.line.ui.dpf

/** Indeterminate progress in the accent colour; static when the user turned animations off. */
class ArcSpinner(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dpf(3.5f); color = context.color(R.color.accent)
    }
    private val track = Paint(paint).apply { color = context.color(R.color.surface_press) }
    private val bounds = RectF()
    private var angle = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) animator.start()
    }

    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val inset = paint.strokeWidth
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, 0f, 360f, false, track)
        canvas.drawArc(bounds, angle - 90f, 100f, false, paint)
    }
}
