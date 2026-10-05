package app.line.ui.calls

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.R
import app.line.ui.*

/** Two or three mutually exclusive views; the thumb slides to the chosen one. */
class SegmentedControl(context: Context, labels: List<CharSequence>, private val onSelect: (Int) -> Unit) : FrameLayout(context) {
    private val thumb = View(context)
    private val items = labels.mapIndexed { index, text ->
        context.label(text, TextStyle.CALLOUT_STRONG, R.color.text_secondary).apply {
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { select(index, animate = true, notify = true) }
        }
    }
    var selected = 0
        private set

    init {
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)
        background = context.roundRect(R.color.surface_raised, Dimens.RADIUS_M)
        thumb.background = context.roundRect(R.color.surface, Dimens.RADIUS_M - 4, R.color.outline)
        addView(thumb, LayoutParams(0, MATCH))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { row.addView(it, LinearLayout.LayoutParams(0, MATCH, 1f)) }
        addView(row, LayoutParams(MATCH, MATCH))
        minimumHeight = dp(48)
        style(0)
    }

    private fun style(index: Int) {
        items.forEachIndexed { i, view ->
            view.isSelected = i == index
            view.setTextColor(context.color(if (i == index) R.color.text_primary else R.color.text_secondary))
        }
    }

    private fun thumbWidth(): Int = if (items.isEmpty()) 0 else (width - paddingLeft - paddingRight) / items.size

    private fun offsetFor(index: Int): Float = (if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f) * index * thumbWidth()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val params = thumb.layoutParams
        params.width = thumbWidth()
        thumb.layoutParams = params
        thumb.translationX = offsetFor(selected)
    }

    fun select(index: Int, animate: Boolean = false, notify: Boolean = false) {
        if (index !in items.indices) return
        val changed = index != selected
        selected = index
        style(index)
        thumb.animate().cancel()
        if (animate && isAttachedToWindow && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            thumb.animate().translationX(offsetFor(index)).setDuration(180).setInterpolator(DecelerateInterpolator(1.6f))
        } else {
            thumb.translationX = offsetFor(index)
        }
        if (changed && notify) onSelect(index)
    }

    fun item(index: Int): TextView = items[index]
}
