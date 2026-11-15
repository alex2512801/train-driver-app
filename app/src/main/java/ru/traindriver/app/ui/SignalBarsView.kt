package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Четыре столбика уровня сигнала SIM-карты (ТЗ раздел 7, HTML-макет .sim-bars) — по
 * возрастанию высоты, закрашены слева направо по [level] (0..4, как SignalStrength.getLevel()),
 * незакрашенные — тёмно-серые, как в макете.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    var level: Int = 0
        set(value) {
            field = value.coerceIn(0, 4)
            invalidate()
        }

    private val onPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val offPaint = Paint().apply { color = Color.rgb(58, 58, 58); isAntiAlias = true }

    // Уже, чем было (26dp→20dp) — машинист попросил сузить весь участок с уровнем сигнала SIM,
    // чтобы рядом освободилось место под значок заряда батареи (см. BatteryIconView).
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(20f).toInt(), dp(22f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barW = dp(3f)
        val gap = dp(1.5f)
        val totalW = barW * 4 + gap * 3
        var x = (width - totalW) / 2f
        val baseline = height.toFloat()
        for (i in 0 until 4) {
            val h = height * (0.3f + i * 0.2333f)
            val paint = if (i < level) onPaint else offPaint
            canvas.drawRect(x, baseline - h, x + barW, baseline, paint)
            x += barW + gap
        }
    }
}
