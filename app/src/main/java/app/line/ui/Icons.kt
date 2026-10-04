package app.line.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.PathParser
import app.line.R
import java.util.concurrent.ConcurrentHashMap

/** Draws one icon of [IconPaths] (24x24 grid, round strokes) tinted by a colour resource. */
class IconView(context: Context, name: String, private var tintRes: Int = R.color.text_primary, private val weight: Float = 2f) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var shapes: List<Pair<Path, Boolean>> = parsed(name)
    private var tint = context.color(tintRes)
    var iconName = name
        private set

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setIcon(name: String) {
        if (name == iconName) return
        iconName = name
        shapes = parsed(name)
        invalidate()
    }

    fun setTint(colorRes: Int) {
        tintRes = colorRes
        tint = context.color(colorRes)
        invalidate()
    }

    fun setTintColor(color: Int) { tint = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width, height) / 24f
        if (scale <= 0f) return
        stroke.color = tint
        fill.color = tint
        stroke.strokeWidth = weight
        canvas.save()
        canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
        canvas.scale(scale, scale)
        for ((path, filled) in shapes) canvas.drawPath(path, if (filled) fill else stroke)
        canvas.restore()
    }

    companion object {
        private val cache = ConcurrentHashMap<String, List<Pair<Path, Boolean>>>()

        fun shapesOf(name: String): List<Pair<Path, Boolean>> = parsed(name)

        private fun parsed(name: String): List<Pair<Path, Boolean>> = cache.getOrPut(name) {
            (IconPaths.get(name) ?: emptyList()).map { PathParser.createPathFromPathData(it.d) to it.fill }
        }
    }
}

fun Context.icon(name: String, tintRes: Int = R.color.text_primary, sizeDp: Int = 24, weight: Float = 2f): IconView =
    IconView(this, name, tintRes, weight).apply { layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }
