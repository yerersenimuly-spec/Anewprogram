package app.line.ui.profile

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.line.R
import app.line.ui.*

/** Scroll container that never grows past [maxHeightPx], so a long list inside a bottom sheet stays reachable. */
class BoundedScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    init { overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

/** A navigation row: icon, title, current value and a chevron. */
class SettingRow(val view: ListRow, private val valueView: TextView) {
    fun setValue(value: CharSequence?) {
        valueView.text = value
        valueView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        view.contentDescription = if (value.isNullOrEmpty()) view.title.text else "${view.title.text}, $value"
    }
}

fun Context.settingRow(icon: String, title: CharSequence, value: CharSequence?, onClick: () -> Unit): SettingRow {
    val valueView = label("", TextStyle.CALLOUT, R.color.text_secondary, maxLines = 1)
    val rowView = ListRow(this).apply {
        this.title.text = title
        leading(tintedIcon(icon))
        trailing(row {
            addView(valueView, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
            addView(icon("chevron_right", R.color.text_tertiary, 20), LinearLayout.LayoutParams(dp(20), dp(20)))
        })
        onClick { onClick() }
    }
    return SettingRow(rowView, valueView).also { it.setValue(value) }
}

/** A row with a switch; tapping anywhere on the row flips it. */
fun Context.switchRow(icon: String?, title: CharSequence, subtitle: CharSequence?, checked: Boolean, onChange: (Boolean) -> Unit): Pair<ListRow, LineSwitch> {
    val toggle = LineSwitch(this).apply { set(checked); onToggle = onChange }
    val rowView = ListRow(this).apply {
        this.title.text = title
        subtitle(subtitle)
        this.title.maxLines = 2
        leading(if (icon != null) tintedIcon(icon) else null)
        trailing(toggle)
        onClick { toggle.performClick() }
        contentDescription = title
    }
    return rowView to toggle
}

/** One choice of a list: a check mark marks the current one. */
fun Context.optionRow(title: CharSequence, subtitle: CharSequence?, selected: Boolean, onClick: () -> Unit): ListRow = ListRow(this).apply {
    this.title.text = title
    subtitle(subtitle)
    leading(null)
    trailing(if (selected) icon("check", R.color.accent, 22, 2.4f) else null)
    isSelected = selected
    onClick { onClick() }
}

fun Context.sheetText(text: CharSequence, colorRes: Int = R.color.text_secondary): TextView =
    label(text, TextStyle.CALLOUT, colorRes).apply { setPadding(dp(Dimens.SCREEN_PADDING), dp(4), dp(Dimens.SCREEN_PADDING), dp(12)) }

fun Context.statusDot(colorRes: Int, sizeDp: Int = 10): View =
    View(this).apply { background = circle(colorRes); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }

fun Context.sheetButton(text: CharSequence, primary: Boolean = false, onClick: () -> Unit): TextView =
    (if (primary) primaryButton(text, onClick) else secondaryButton(text, onClick)).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dp(Dimens.SCREEN_PADDING); marginEnd = dp(Dimens.SCREEN_PADDING); topMargin = dp(8) }
    }

fun ViewGroup.maxSheetHeight(): Int = (resources.displayMetrics.heightPixels * 0.62f).toInt()
