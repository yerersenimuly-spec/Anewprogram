package app.line.ui

import android.animation.TimeInterpolator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.line.R

data class SheetAction(
    val icon: String,
    val label: CharSequence,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Bottom sheet: the one container for menus, confirmations and short forms.
 * It slides up, closes on outside tap or back, and resizes above the keyboard.
 */
class Sheet(private val activity: Activity, private val bottomInset: Int = 0) {
    private val dialog = Dialog(activity)
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val container = FrameLayout(activity)
    var onDismiss: (() -> Unit)? = null
    private var closing = false

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply { windowAnimations = 0 }
        }
        val radius = activity.dpf(Dimens.RADIUS_XL.toFloat())
        body.background = GradientDrawable().apply {
            setColor(activity.color(R.color.surface))
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        body.setPadding(0, activity.dp(10), 0, activity.dp(12) + bottomInset)
        val handle = View(activity).apply { background = activity.roundRect(R.color.outline, 3) }
        body.addView(handle, LinearLayout.LayoutParams(activity.dp(40), activity.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = activity.dp(10)
        })
        container.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onDismiss?.invoke() }
        dialog.setOnDismissListener { onDismiss?.invoke().also { onDismiss = null } }
    }

    fun title(text: CharSequence): Sheet = apply {
        body.addView(activity.label(text, TextStyle.TITLE), LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = activity.dp(Dimens.SCREEN_PADDING); marginEnd = activity.dp(Dimens.SCREEN_PADDING); bottomMargin = activity.dp(6)
        })
    }

    fun message(text: CharSequence): Sheet = apply {
        body.addView(activity.label(text, TextStyle.CALLOUT, R.color.text_secondary), LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = activity.dp(Dimens.SCREEN_PADDING); marginEnd = activity.dp(Dimens.SCREEN_PADDING); bottomMargin = activity.dp(14)
        })
    }

    fun content(view: View): Sheet = apply { body.addView(view, LinearLayout.LayoutParams(MATCH, WRAP)) }

    fun actions(items: List<SheetAction>): Sheet = apply {
        items.forEach { action ->
            val tint = if (action.destructive) R.color.negative else R.color.text_primary
            body.addView(ListRow(activity).apply {
                title.text = action.label
                title.setTextColor(activity.color(tint))
                leading(activity.icon(action.icon, if (action.destructive) R.color.negative else R.color.text_secondary, 22))
                onClick { dismiss(); action.onClick() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** Two stacked buttons: the main action and a dismissal. */
    fun buttons(primary: CharSequence, destructive: Boolean = false, secondary: CharSequence?, onPrimary: () -> Unit): Sheet = apply {
        val column = activity.column { setPadding(activity.dp(Dimens.SCREEN_PADDING), activity.dp(4), activity.dp(Dimens.SCREEN_PADDING), 0) }
        val main = if (destructive) activity.destructiveButton(primary) { dismiss(); onPrimary() } else activity.primaryButton(primary) { dismiss(); onPrimary() }
        column.addView(main, LinearLayout.LayoutParams(MATCH, WRAP))
        if (secondary != null) column.addView(activity.textButton(secondary, R.color.text_secondary) { dismiss() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = activity.dp(4) })
        body.addView(column, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun show(): Sheet = apply {
        dialog.show()
        container.post {
            body.translationY = body.height.toFloat()
            body.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    fun dismiss() {
        if (closing || !dialog.isShowing) return
        closing = true
        body.animate().translationY(body.height.toFloat()).setDuration(160).setInterpolator(TimeInterpolator { it * it })
            .withEndAction { if (dialog.isShowing) dialog.dismiss() }.start()
    }

    val isShowing: Boolean get() = dialog.isShowing
}

fun android.content.Context.destructiveButton(text: CharSequence, onClick: () -> Unit) =
    label(text, TextStyle.BODY_STRONG, R.color.on_accent).apply {
        gravity = Gravity.CENTER
        minHeight = dp(52)
        setPadding(dp(28), 0, dp(28), 0)
        background = ripple(roundRect(R.color.negative, 16), 16)
        isClickable = true; isFocusable = true
        setOnClickListener { UiSounds.play(context, UiCue.TAP); onClick() }
        Motion.press(this)
    }
