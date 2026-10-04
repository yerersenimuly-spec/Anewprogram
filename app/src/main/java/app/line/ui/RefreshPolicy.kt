package app.line.ui

import android.app.Activity
import android.os.Build
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object RefreshPolicy {
    private const val MAX_REFRESH_RATE = 120f

    private val requestedViews = WeakHashMap<Activity, WeakReference<View>>()

    internal data class ModeRate(
        val width: Int,
        val height: Int,
        val refreshRate: Float,
        val seamless: Boolean = true,
    )

    internal fun ratesForResolution(
        modes: List<ModeRate>,
        width: Int,
        height: Int,
    ): List<Float> = modes.asSequence()
        .filter { it.width == width && it.height == height && it.seamless }
        .map { it.refreshRate }
        .toList()

    internal fun preferredRate(rates: List<Float>, current: Float): Float {
        val supported = rates.filter { it.isFinite() && it > 0f }
        return supported.filter { it <= MAX_REFRESH_RATE }.maxOrNull()
            ?: supported.firstOrNull { it == current }
            ?: 0f
    }

    @Suppress("DEPRECATION")
    fun apply(activity: Activity, view: View) {
        clear(activity)

        val display = if (Build.VERSION.SDK_INT >= 30) {
            activity.display ?: activity.windowManager.defaultDisplay
        } else {
            activity.windowManager.defaultDisplay
        }
        val currentMode = display.mode
        val seamlessRates = if (Build.VERSION.SDK_INT >= 31) {
            currentMode.alternativeRefreshRates.toSet()
        } else {
            // Older APIs cannot report seamless alternatives, so keep the current mode.
            emptySet()
        }
        val modes = display.supportedModes.map {
            ModeRate(
                it.physicalWidth,
                it.physicalHeight,
                it.refreshRate,
                seamless = it.refreshRate == currentMode.refreshRate || it.refreshRate in seamlessRates,
            )
        }
        val rates = ratesForResolution(modes, currentMode.physicalWidth, currentMode.physicalHeight)
        val rate = preferredRate(rates, currentMode.refreshRate)
        if (!rate.isFinite() || rate <= 0f || rate > MAX_REFRESH_RATE) return

        // This is a preference only; Android and the device retain display and power control.
        if (Build.VERSION.SDK_INT >= 35) {
            view.setRequestedFrameRate(rate)
            synchronized(requestedViews) {
                requestedViews[activity] = WeakReference(view)
            }
        } else {
            val attributes = activity.window.attributes
            attributes.preferredRefreshRate = rate
            activity.window.attributes = attributes
        }
    }

    fun clear(activity: Activity) {
        val requestedView = synchronized(requestedViews) {
            requestedViews.remove(activity)?.get()
        }
        if (Build.VERSION.SDK_INT >= 35) {
            requestedView?.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
        }

        val attributes = activity.window.attributes
        if (attributes.preferredRefreshRate != 0f) {
            attributes.preferredRefreshRate = 0f
            activity.window.attributes = attributes
        }
    }
}
