package ru.traindriver.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Кнопка "окошко прибытия/отправления" в шапке — раньше был эмодзи "⏱" (StopwatchIconView
 * из-за реального бага на телефоне: на некоторых системных шрифтах этот символ не
 * отображается вообще (пустое место, невидимая кнопка) — так и было у машиниста ("не нашёл,
 * где эта кнопка"). Тот же приём, что уже применён к значку "Ограничения" (OpasnoeIconView):
 * рисуем секундомер сами, чтобы не зависеть от эмодзи-шрифта конкретного устройства.
 */
class StopwatchIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(40f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f + dp(2f)
        val r = width * 0.3f

        // Кнопка/стебелёк сверху циферблата.
        canvas.drawLine(cx, cy - r - dp(6f), cx, cy - r - dp(1f), paint)
        // Сам циферблат.
        canvas.drawCircle(cx, cy, r, paint)
        // Стрелка (примерно "10 минут одиннадцатого") — чтобы читалось как часы/секундомер,
        // а не просто кольцо.
        canvas.drawLine(cx, cy, cx + r * 0.55f, cy - r * 0.55f, paint)
    }
}
