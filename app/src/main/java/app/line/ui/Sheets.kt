package app.line.ui

import android.animation.TimeInterpolator
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * Bottom sheet rendered as a layer of the activity window itself. A floating Dialog window is avoided
 * on purpose: on some devices its width collapses to the smallest child (the grabber) and the whole
 * sheet turns into a narrow vertical strip. As a view layer the sheet always spans the screen width.
 */
class Sheet(private val activity: Activity, private val bottomInset: Int = 0) {
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var layer: FrameLayout? = null
    private var closing = false
    var onDismiss: (() -> Unit)? = null

    init {
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
    }

    val isShowing: Boolean get() = layer != null

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
        if (layer != null) return this
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return this
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(activity.color(R.color.scrim))
            isClickable = true
            setOnClickListener { dismiss() }
        }
        val panel = FrameLayout(activity).apply { isClickable = true }
        panel.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        scrim.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        content.addView(scrim, ViewGroup.LayoutParams(MATCH, MATCH))
        layer = scrim
        Sheets.shown.add(this)
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(150).start()
        panel.post {
            if (layer === scrim) {
                body.translationY = body.height.toFloat()
                body.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
    }

    fun dismiss() {
        val scrim = layer ?: return
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(160).start()
        body.animate().translationY(body.height.toFloat()).setDuration(160)
            .setInterpolator(TimeInterpolator { it * it })
            .withEndAction {
                (scrim.parent as? ViewGroup)?.removeView(scrim)
                if (layer === scrim) layer = null
                closing = false
                Sheets.shown.remove(this)
                onDismiss?.invoke()
                onDismiss = null
            }.start()
    }
}

/** The sheets currently on screen; the newest one consumes the back press. */
object Sheets {
    internal val shown = java.util.concurrent.CopyOnWriteArrayList<Sheet>()

    fun dismissTop(): Boolean {
        val top = shown.lastOrNull() ?: return false
        top.dismiss()
        return true
    }
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
