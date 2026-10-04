package app.line.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class LineIcon(context: Context, private val name: String, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = if (name == "about") 1.9f else 1.7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        when (name) {
            "about" -> {
                moveTo(12f, 21f)
                cubicTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
                cubicTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                cubicTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                cubicTo(3f, 16.97f, 7.03f, 21f, 12f, 21f); close()
                moveTo(12f, 10.8f); lineTo(12f, 16.2f)
                moveTo(12f, 7.5f); lineTo(12f, 7.5f)
            }
            "bell" -> {
                moveTo(5f, 17f); lineTo(19f, 17f); lineTo(17f, 14f); lineTo(17f, 9f)
                cubicTo(17f, 2f, 7f, 2f, 7f, 9f); lineTo(7f, 14f); close()
                moveTo(10f, 21f); quadTo(12f, 23f, 14f, 21f)
                moveTo(12f, 2f); lineTo(12f, 3f)
            }
            "search" -> { addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW); moveTo(15.5f, 15.5f); lineTo(21f, 21f) }
            "globe" -> {
                addCircle(12f, 12f, 9f, Path.Direction.CW); addOval(8f, 3f, 16f, 21f, Path.Direction.CW)
                moveTo(3f, 12f); lineTo(21f, 12f)
            }
            "compose" -> {
                moveTo(13f, 4f); lineTo(5f, 4f); quadTo(3f, 4f, 3f, 6f); lineTo(3f, 19f)
                quadTo(3f, 21f, 5f, 21f); lineTo(18f, 21f); quadTo(20f, 21f, 20f, 19f); lineTo(20f, 12f)
                moveTo(10f, 14f); lineTo(11f, 10f); lineTo(18f, 3f); lineTo(21f, 6f); lineTo(14f, 13f); close()
            }
            "shield" -> {
                moveTo(12f, 3f); lineTo(21f, 6f); lineTo(20f, 14f)
                quadTo(18f, 19f, 12f, 22f); quadTo(6f, 19f, 4f, 14f); lineTo(3f, 6f); close()
                moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
            }
            "more" -> {
                addCircle(5f, 12f, 0.8f, Path.Direction.CW); addCircle(12f, 12f, 0.8f, Path.Direction.CW); addCircle(19f, 12f, 0.8f, Path.Direction.CW)
            }
            "phone" -> {
                moveTo(5f, 3f); lineTo(9f, 3f); lineTo(10.5f, 8f); lineTo(8f, 10f)
                cubicTo(9.5f, 13f, 11f, 14.5f, 14f, 16f); lineTo(16f, 13.5f); lineTo(21f, 15f)
                lineTo(21f, 19f); cubicTo(21f, 22f, 15f, 21f, 9f, 16f)
                cubicTo(3f, 11f, 2f, 5f, 5f, 3f); close()
            }
            "chat" -> {
                moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 16f)
                quadTo(21f, 18f, 19f, 18f); lineTo(9f, 18f); lineTo(4f, 21f); lineTo(4f, 18f)
                quadTo(3f, 18f, 3f, 16f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f)
                moveTo(7f, 9f); lineTo(17f, 9f); moveTo(7f, 13f); lineTo(14f, 13f)
            }
            "person" -> {
                addCircle(12f, 7f, 3.5f, Path.Direction.CW)
                moveTo(4f, 21f); cubicTo(4f, 12f, 20f, 12f, 20f, 21f)
            }
            "settings" -> {
                addCircle(12f, 12f, 3f, Path.Direction.CW)
                moveTo(9f, 3f); lineTo(15f, 3f); lineTo(16f, 6f); lineTo(19f, 7f)
                lineTo(22f, 11f); lineTo(20f, 14f); lineTo(19f, 17f); lineTo(15f, 20f)
                lineTo(12f, 19f); lineTo(9f, 20f); lineTo(5f, 17f); lineTo(4f, 14f)
                lineTo(2f, 11f); lineTo(5f, 7f); lineTo(8f, 6f); close()
            }
            "copy" -> {
                addRoundRect(8f, 8f, 21f, 21f, 2f, 2f, Path.Direction.CW)
                moveTo(15f, 4f); lineTo(5f, 4f); quadTo(3f, 4f, 3f, 6f); lineTo(3f, 15f)
            }
            "arrow" -> { moveTo(5f, 12f); lineTo(19f, 12f); moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f) }
            "send" -> { moveTo(12f, 20f); lineTo(12f, 4f); moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f) }
            "back" -> { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
            "chevron" -> { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
            "add" -> { moveTo(12f, 4f); lineTo(12f, 20f); moveTo(4f, 12f); lineTo(20f, 12f) }
            "check" -> { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) }
            "delete" -> {
                moveTo(8f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(8f, 19f); lineTo(2f, 12f); close()
                moveTo(11f, 9f); lineTo(17f, 15f); moveTo(17f, 9f); lineTo(11f, 15f)
            }
            "mic", "muted" -> {
                addRoundRect(9f, 3f, 15f, 14f, 3f, 3f, Path.Direction.CW)
                moveTo(5f, 10f); cubicTo(5f, 20f, 19f, 20f, 19f, 10f)
                moveTo(12f, 18f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f)
                if (name == "muted") { moveTo(3f, 3f); lineTo(21f, 21f) }
            }
            "speaker" -> {
                moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
                moveTo(16f, 8f); quadTo(20f, 12f, 16f, 16f)
                moveTo(19f, 5f); quadTo(26f, 12f, 19f, 19f)
            }
            "key" -> {
                addCircle(8f, 9f, 5f, Path.Direction.CW)
                moveTo(12f, 13f); lineTo(21f, 22f); moveTo(17f, 18f); lineTo(20f, 15f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24f, size / 24f); canvas.drawPath(path, paint); canvas.restore()
    }
}
