package app.line.ui.calls

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.line.R
import app.line.ui.*

/** Digits 1–9, then add participant, 0 and backspace. Keys flex in height so the pad fits short screens. */
class KeypadView(
    context: Context,
    addDescription: String,
    backspaceDescription: String,
    private val onDigit: (Char) -> Unit,
    private val onAdd: () -> Unit,
    private val onBackspace: () -> Unit,
    private val onClear: () -> Unit,
) : LinearLayout(context) {
    private val add: View
    private val backspace: View

    init {
        orientation = VERTICAL
        val rows = listOf("123", "456", "789")
        rows.forEach { digits ->
            addView(row(digits.map { digitKey(it) }), LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(GAP) })
        }
        add = utilityKey("plus", addDescription).also { it.setOnClickListener { press(it); onAdd() } }
        backspace = utilityKey("backspace", backspaceDescription).also {
            it.setOnClickListener { press(it); onBackspace() }
            it.setOnLongClickListener { view -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onClear(); true }
        }
        addView(row(listOf(add, digitKey('0'), backspace)), LayoutParams(MATCH, 0, 1f))
    }

    private fun row(keys: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        keys.forEachIndexed { i, key ->
            addView(key, LayoutParams(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(GAP) })
        }
    }

    private fun press(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        UiSounds.play(context, UiCue.TAP)
    }

    private fun digitKey(digit: Char): TextView = context.label(digit.toString(), TextStyle.HEADLINE).apply {
        textSize = 30f
        typeface = Fonts.get(context, 500)
        gravity = Gravity.CENTER
        minHeight = dp(MIN_KEY)
        background = context.ripple(context.roundRect(R.color.surface, Dimens.RADIUS_L), Dimens.RADIUS_L)
        isClickable = true; isFocusable = true
        setOnClickListener { press(this); onDigit(digit) }
        Motion.press(this)
    }

    private fun utilityKey(icon: String, description: String): FrameLayout = FrameLayout(context).apply {
        minimumHeight = dp(MIN_KEY)
        background = context.ripple(context.roundRect(R.color.surface_raised, Dimens.RADIUS_L), Dimens.RADIUS_L)
        contentDescription = description
        isClickable = true; isFocusable = true
        addView(context.icon(icon, R.color.text_primary, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        Motion.press(this)
    }

    fun setAddEnabled(enabled: Boolean) = enable(add, enabled)
    fun setBackspaceEnabled(enabled: Boolean) = enable(backspace, enabled)

    private fun enable(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    private companion object {
        const val GAP = 8
        const val MIN_KEY = 52
    }
}
